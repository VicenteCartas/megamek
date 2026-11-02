/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.common.Configuration;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.GunEmplacement;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.AbstractBuildingEntity;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Actual equipment files, production GLB loader, assembler, LOD switching and game shader. */
@Tag("on-demand")
class GpuBuildingTurretSmokeTest {
    private record Fixture(String label, Entity entity) { }
    private record View(String label, GpuUnitInstance instance, int triangles) { }

    @Test
    void actualLoadoutsKeepAllWeaponsAndEmittersAtBothDetailLevels() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1800, 1600);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var library = new GpuUnitModels();
                var models = new ArrayList<GpuUnitModel>();
                var batch = new ModelBatch(GpuUnitShader.provider());
                try {
                    EquipmentType.initializeTypes();
                    var fixtures = new ArrayList<Fixture>();
                    for (var entry : List.of(
                          "Light Turrets/Light Sniper Turret 2300.blk",
                          "Medium Turrets/Medium Sniper Turret 2300.blk",
                          "Medium Turrets/Medium Missile Turret 3039.blk",
                          "Heavy Turrets/Heavy Laser Turret 2300.blk",
                          "Heavy Turrets/Heavy Shredder Turret 2300.blk",
                          "Heavy Turrets/Heavy Flak Turret 2300.blk",
                          "Heavy Turrets/Heavy Missile Turret 2300.blk")) {
                        var entity = new MekFileParser(new File(Configuration.dataDir(), "mekfiles/unit_files.zip"),
                              "ge/" + entry).getEntity();
                        fixtures.add(new Fixture(entity.getChassis(), entity));
                    }
                    fixtures.add(example("Single PPC", "PPC"));
                    fixtures.add(example("Twin LRM 20", "LRM 20", "LRM 20"));
                    fixtures.add(example("AC + SRM 6", "Autocannon/5", "SRM 6"));
                    fixtures.add(example("Twin large lasers", "Large Laser", "Large Laser"));
                    fixtures.add(example("SRM 2 + 4 + 6", "SRM 2", "SRM 4", "SRM 6"));
                    fixtures.add(example("Twin LRM 5 pods", "LRM 5", "LRM 5"));
                    fixtures.add(example("Single SRM 4 pod", "SRM 4"));
                    fixtures.add(example("LRM 10 + AC + PPC", "LRM 10", "Autocannon/10", "PPC"));
                    fixtures.add(example("Quad SRM 6", "SRM 6", "SRM 6", "SRM 6", "SRM 6"));
                    fixtures.add(example("Six LRM 15 + AC", "LRM 15", "LRM 15", "LRM 15", "LRM 15", "LRM 15", "LRM 15",
                          "Autocannon/5"));
                    fixtures.add(example("Gauss + PPC", "ISGaussRifle", "PPC"));
                    fixtures.add(example("Long Tom", "ISLongTom"));
                    fixtures.add(example("Sniper + Thumper", "ISSniper", "ISThumper"));
                    fixtures.add(example("Twin RAC/5", "ISRotaryAC5", "ISRotaryAC5"));
                    fixtures.add(example("Heavy Gauss + RAC/2", "ISHeavyGaussRifle", "ISRotaryAC2"));
                    fixtures.add(example("Light Gauss + HAG/40", "ISLightGaussRifle", "CLHAG40"));
                    fixtures.add(example("HAG/20 + HAG/30", "CLHAG20", "CLHAG30"));
                    fixtures.add(example("Plasma cannon + rifle", "CLPlasmaCannon", "ISPlasmaRifle"));
                    fixtures.add(example("AC/10 + flamers", "Autocannon/10", "Flamer", "Heavy Flamer"));
                    fixtures.add(example("MML 9 + ATM 12", "ISMML9", "CLATM12"));
                    fixtures.add(example("MRM 40 + Arrow IV", "MRM 40", "ISArrowIV"));
                    fixtures.add(example("Thunderbolt 20 + mortar 8", "Thunderbolt 20", "ISMekMortar8"));
                    var recipe = library.descriptor(BuildingTurretVisual.ROOT + "turret.json");
                    var unsupported = UnitModelState.capture(example("Screen launcher", "Screen Launcher").entity());
                    assertThrows(IllegalArgumentException.class,
                          () -> BuildingTurretVisual.assemble(library, recipe, unsupported.structure()),
                          "An unsupported weapon must not silently become a small legacy module");
                    // Any mix assembles: launchers take side arms, extra exposed guns and launchers use front bays.
                    var mixedLoadout = BuildingTurretVisual.assemble(library, recipe, UnitModelState.capture(
                          example("LRM + AC + PPC", "LRM 10", "Autocannon/10", "PPC").entity()).structure());
                    models.add(mixedLoadout);
                    assertEquals(1, arms(mixedLoadout, "right"), "A launcher beside front guns rides a flank arm");
                    assertEquals(0, arms(mixedLoadout, "left"), "The unused flank keeps no bracket");
                    assertEquals(2, mixedLoadout.equipment().size() - arms(mixedLoadout, "right") - roofMounted(mixedLoadout),
                          "The cannon and the PPC sit in front bays");
                    var quad = BuildingTurretVisual.assemble(library, recipe, UnitModelState.capture(
                          example("Quad SRM 6", "SRM 6", "SRM 6", "SRM 6", "SRM 6").entity()).structure());
                    models.add(quad);
                    assertEquals(2, arms(quad, "left"), "The pillar stacks two launchers per side");
                    assertEquals(2, arms(quad, "right"));
                    var overflow = BuildingTurretVisual.assemble(library, recipe, UnitModelState.capture(example("Seven",
                          "LRM 15", "LRM 15", "LRM 15", "LRM 15", "LRM 15", "LRM 15", "Autocannon/5").entity()).structure());
                    models.add(overflow);
                    assertEquals(4, arms(overflow, "left") + arms(overflow, "right"));
                    assertEquals(1, roofMounted(overflow), "The pillar roof takes one launcher");
                    assertEquals(2, overflow.equipment().size() - 4 - roofMounted(overflow),
                          "A launcher beyond the pillar's mounts sits encased in the front bay with the cannon");
                    var exposedBank = new com.badlogic.gdx.utils.JsonReader().parse(recipe.toString());
                    exposedBank.addChild("installations", new com.badlogic.gdx.utils.JsonReader().parse(
                          "{\"center\":{\"encased\":false},\"left-1\":{\"encased\":false},\"right-1\":{\"encased\":false}}"));
                    var threeGuns = BuildingTurretVisual.assemble(library, exposedBank, UnitModelState.capture(
                          example("Three external guns", "Autocannon/5", "Autocannon/5", "Autocannon/5").entity()).structure());
                    models.add(threeGuns);
                    assertEquals(2, roofMounted(threeGuns), "Two roof guns; the third exposed gun moves into a front bay");
                    var mixedGuns = new com.badlogic.gdx.utils.JsonReader().parse(recipe.toString());
                    mixedGuns.addChild("installations", new com.badlogic.gdx.utils.JsonReader().parse(
                          "{\"left\":{\"encased\":false},\"right\":{\"encased\":true}}"));
                    var exposedAndFront = BuildingTurretVisual.assemble(library, mixedGuns, UnitModelState.capture(
                          example("Mixed cannon installation", "Autocannon/5", "Autocannon/5").entity()).structure());
                    models.add(exposedAndFront);
                    var roofGun = exposedAndFront.equipment().stream().filter(b -> trunnion(exposedAndFront, b).z > 16)
                          .findFirst().orElseThrow();
                    assertEquals(BuildingTurretVisual.FRONT_BAY_DEPRESSION, roofGun.depression(), .001f,
                          "An exposed cannon stops depressing before it reaches the front-mounted cannon");
                    var report = new StringBuilder("Fixture,LOD0,LOD1,bindings\n");
                    var levels = List.of(new ArrayList<View>(), new ArrayList<View>());
                    for (var fixture : fixtures) {
                        var state = UnitModelState.capture(fixture.entity());
                        var model = BuildingTurretVisual.assemble(library, recipe, state.structure());
                        models.add(model);
                        var weapons = fixture.entity().getWeaponList();
                        for (var weapon : weapons) {
                            var binding = model.equipment().stream().filter(b -> b.index() == weapon.getEquipmentNum()).findFirst().orElseThrow();
                            assertFalse(binding.embedded(), fixture.label());
                            assertFalse(binding.emitters().isEmpty(), fixture.label());
                            assertTrue(binding.asset().startsWith(BuildingTurretVisual.ROOT), binding.asset());
                        }
                        assertEquals(model.equipment().size(), model.equipment().stream().map(b -> b.index()).distinct().count());
                        int[] counts = new int[2];
                        for (int lod = 0; lod < 2; lod++) {
                            var instance = new GpuUnitInstance(model);
                            instance.bodyDetail(lod == 0 ? 100 : .01f, lod == 0);
                            assertEquals(lod, instance.detailLevel(), fixture.label());
                            counts[lod] = triangles(instance.nodes);
                            for (var binding : model.equipment()) {
                                assertTrue(triangles(List.of(instance.getNode(binding.node()))) > 0, fixture.label() + " LOD" + lod);
                                for (var emitter : binding.emitters()) {
                                    assertNotNull(instance.getNode(emitter.node()), emitter.node());
                                    var p = new Vector3(); var d = new Vector3();
                                    UnitModelAttachment.emitter(instance, emitter, p, d);
                                    assertTrue(Float.isFinite(p.len()) && Math.abs(d.len()-1) < .001f);
                                }
                            }
                            levels.get(lod).add(new View(fixture.label(), instance, counts[lod]));
                        }
                        report.append(fixture.label()).append(',').append(counts[0]).append(',').append(counts[1])
                              .append(',').append(model.equipment().size()).append('\n');
                        assertTrue(counts[1] < counts[0], fixture.label());
                        checkRecoil(model);
                        checkRetainedYaw(model, state);
                    }
                    Path output = Path.of(System.getProperty("megamek.gpu.screenshots"));
                    Files.createDirectories(output);
                    Files.writeString(output.resolve("turret-palette-counts.csv"), report);
                    // Working flamers keep a pilot flame at their nozzles; a destroyed flamer has none.
                    var pilots = new GpuPilotFlames();
                    try {
                        var flamers = example("Flamers", "Flamer", "Heavy Flamer").entity();
                        var lit = BuildingTurretVisual.assemble(library, recipe, UnitModelState.capture(flamers).structure());
                        models.add(lit);
                        pilotPalette(batch, pilots, lit, UnitModelState.capture(flamers), output);
                        assertEquals(2, pilots.pilotCount(), "Each working flamer burns a pilot flame");
                        flamers.getWeaponList().getFirst().setDestroyed(true);
                        pilots(pilots, lit, UnitModelState.capture(flamers), 0);
                        assertEquals(1, pilots.pilotCount(), "A destroyed flamer's pilot is out");
                    } finally { pilots.dispose(); }
                    // A fort hex: its roof turret, and four weapons on each of three walls, two to a casemate.
                    var fort = (AbstractBuildingEntity) new MekFileParser(
                          new File("testresources/megamek/common/units/Hardened Command Fort (Clan Invasion).blk")).getEntity();
                    fort.setPosition(new Coords(0, 0));
                    var fortState = UnitModelState.capture(fort, null, 0);
                    var fortHex = BuildingTurretVisual.assemble(library, recipe, fortState.structure());
                    models.add(fortHex);
                    for (var weapon : fort.getWeaponsAt(fort.getSecondaryPositions().get(0))) {
                        assertTrue(fortHex.equipment().stream().anyMatch(b -> b.index() == weapon.getEquipmentNum()),
                              "Every weapon of the hex is shown: " + weapon.getName());
                    }
                    checkCasemates(fortHex, fortState);
                    library.setDisplayMode(UnitDisplayMode.ALL_MEEPLES, false);
                    assertNotNull(library.get(new BoardScene.UnitModel(BuildingTurretVisual.ROOT + "turret.json", "", "", 1, 0,
                          BoardScene.LocationDamage.NONE, fortState), 1, 0), "A turret shows its weapons even with All Meeples");
                    library.setDisplayMode(UnitDisplayMode.MODELS, false);
                    for (int lod = 0; lod < 2; lod++) {
                        palette(batch, levels.get(lod), output, "turret-palette", lod, false);
                        palette(batch, levels.get(lod), output, "turret-palette", lod, true);
                        palette(batch, levels.get(lod).subList(0, 4), output, "receiver-housings-rear", lod, false);
                    }
                    var completeWeapons = List.of(new ArrayList<View>(), new ArrayList<View>());
                    for (var fixture : List.of(example("AC/2", "Autocannon/2"), example("AC/5", "Autocannon/5"),
                          example("AC/10", "Autocannon/10"), example("AC/20", "Autocannon/20"), example("MG", "Machine Gun"),
                          example("Small laser", "Small Laser"), example("Medium laser", "Medium Laser"),
                          example("Large laser", "Large Laser"), example("Light PPC", "Light PPC"),
                          example("PPC", "PPC"), example("Heavy PPC", "Heavy PPC"), example("Gauss", "ISGaussRifle"),
                          example("Long Tom", "ISLongTom"), example("RAC/5", "ISRotaryAC5"),
                          example("Light Gauss", "ISLightGaussRifle"), example("Heavy Gauss", "ISHeavyGaussRifle"),
                          example("HAG/20", "CLHAG20"), example("HAG/40", "CLHAG40"),
                          example("Plasma cannon", "CLPlasmaCannon"), example("Flamer", "Flamer"),
                          example("Twin AC", "Autocannon/5", "Autocannon/5"), example("AC + PPC", "Autocannon/5", "PPC"))) {
                        for (boolean encased : new boolean[] { false, true }) {
                            var spec = new com.badlogic.gdx.utils.JsonReader().parse(recipe.toString());
                            spec.addChild("installations", new com.badlogic.gdx.utils.JsonReader().parse(
                                  "{\"center\":{\"encased\":" + encased + "},\"left\":{\"encased\":" + encased
                                        + "},\"right\":{\"encased\":" + encased + "}}"));
                            var state = UnitModelState.capture(fixture.entity());
                            var model = BuildingTurretVisual.assemble(library, spec, state.structure()); models.add(model);
                            checkRecoil(model); checkRetainedYaw(model, state);
                            assertEquals(encased ? 0 : model.equipment().size(), roofMounted(model), fixture.label());
                            for (var binding : model.equipment()) {
                                assertTrue(binding.pitchOnly(), "Every gun pitches on its trunnion");
                                if (!encased) {
                                    // The roof sockets stand 11 up and the posts' pins 7.5 above them.
                                    var hinge = trunnion(model, binding);
                                    assertEquals(18.5f, hinge.z, .01f, "The roof posts hold the gun by its trunnion");
                                    assertNull(model.instance.getNode(binding.node() + "-mantlet"), "A roof gun has no bay mantlet");
                                } else {
                                    assertNull(model.instance.getNode(binding.node() + "-receiver"), "A bay hides the receiver: left out");
                                }
                            }
                            for (int lod = 0; lod < 2; lod++) {
                                var instance = new GpuUnitInstance(model); instance.bodyDetail(lod == 0 ? 100 : .01f, lod == 0);
                                completeWeapons.get(lod).add(new View(fixture.label() + (encased ? " | armored bay" : " | exposed"),
                                      instance, triangles(instance.nodes)));
                            }
                        }
                    }
                    for (int lod = 0; lod < 2; lod++) {
                        palette(batch, completeWeapons.get(lod), output, "weapon-installations", lod, false);
                        palette(batch, completeWeapons.get(lod), output, "weapon-installations", lod, true);
                    }
                    var pitchedLaunchers = List.of(new ArrayList<View>(), new ArrayList<View>());
                    for (var fixture : List.of(example("AC + SRM 4", "Autocannon/5", "SRM 4"),
                          example("AC + LRM 20", "Autocannon/5", "LRM 20"),
                          example("Twin LRM 20", "LRM 20", "LRM 20"))) {
                        var state = UnitModelState.capture(fixture.entity());
                        var model = BuildingTurretVisual.assemble(library, recipe, state.structure()); models.add(model);
                        for (int lod = 0; lod < 2; lod++) {
                            for (int pitch : new int[] { -20, 0, 25 }) {
                                var instance = new GpuUnitInstance(model);
                                instance.bodyDetail(lod == 0 ? 100 : .01f, lod == 0);
                                for (var binding : model.equipment()) {
                                    if (binding.pitchOnly()) {
                                        instance.getNode(binding.aimNode()).rotation.set(Vector3.X, pitch);
                                    }
                                }
                                instance.calculateTransforms();
                                pitchedLaunchers.get(lod).add(new View(fixture.label() + " | pitch " + pitch,
                                      instance, triangles(instance.nodes)));
                            }
                        }
                    }
                    for (int lod = 0; lod < 2; lod++) {
                        palette(batch, pitchedLaunchers.get(lod), output, "mixed-installations", lod, false);
                    }
                    // The shared roof mounts side by side: a fork of posts, one post, a pedestal; at rest and elevated.
                    var roofMounts = List.of(new ArrayList<View>(), new ArrayList<View>());
                    for (var fixture : List.of(example("LRM 20", "LRM 20"), example("AC/20 exposed", "Autocannon/20"),
                          example("PPC exposed", "PPC"),
                          example("AC + PPC + 4 SRM 6", "Autocannon/5", "PPC", "SRM 6", "SRM 6", "SRM 6", "SRM 6"),
                          example("Arrow IV", "ISArrowIV"))) {
                        for (String style : List.of("posts", "post", "pedestal")) {
                            var spec = new com.badlogic.gdx.utils.JsonReader().parse(recipe.toString());
                            spec.addChild("roofMount", new com.badlogic.gdx.utils.JsonValue(style));
                            if (fixture.label().endsWith("exposed")) {
                                spec.addChild("installations", new com.badlogic.gdx.utils.JsonReader().parse(
                                      "{\"center\":{\"encased\":false}}"));
                            }
                            var model = BuildingTurretVisual.assemble(library, spec,
                                  UnitModelState.capture(fixture.entity()).structure()); models.add(model);
                            assertTrue(roofMounted(model) > 0, fixture.label() + " " + style);
                            for (int lod = 0; lod < 2; lod++) {
                                for (int pitch : new int[] { 0, 25 }) {
                                    var instance = new GpuUnitInstance(model);
                                    instance.bodyDetail(lod == 0 ? 100 : .01f, lod == 0);
                                    for (var binding : model.equipment()) {
                                        instance.getNode(binding.aimNode()).rotation.set(Vector3.X, pitch);
                                    }
                                    instance.calculateTransforms();
                                    roofMounts.get(lod).add(new View(fixture.label() + " | " + style + " | pitch " + pitch,
                                          instance, triangles(instance.nodes)));
                                }
                            }
                        }
                    }
                    for (int lod = 0; lod < 2; lod++) {
                        palette(batch, roofMounts.get(lod), output, "roof-mounts", lod, false);
                        var single = roofMounts.get(lod).stream().filter(view -> !view.label().startsWith("AC + PPC")).toList();
                        palette(batch, single, output, "roof-mounts-close", lod, false);
                    }
                    var exposures = List.of(new ArrayList<View>(), new ArrayList<View>());
                    var launchers = List.of(new ArrayList<View>(), new ArrayList<View>());
                    for (var example : List.of(example("AC", "Autocannon/5"), example("Laser", "Medium Laser"),
                          example("PPC", "PPC"), example("SRM 2", "SRM 2"), example("SRM 4", "SRM 4"),
                          example("SRM 6", "SRM 6"), example("LRM 5", "LRM 5"), example("LRM 10", "LRM 10"),
                          example("LRM 15", "LRM 15"), example("LRM 20", "LRM 20"))) {
                        var state = UnitModelState.capture(example.entity());
                        var full = BuildingTurretVisual.assemble(library, recipe, state.structure()); models.add(full);
                        var emitter = full.equipment().getFirst().emitters().getFirst();
                        var module = library.modular(full.equipment().getFirst().asset());
                        boolean launcher = module.descriptor().family().equals("missile");
                        if (launcher) {
                            var invalid = new com.badlogic.gdx.utils.JsonReader().parse(recipe.toString());
                            invalid.addChild("installations", new com.badlogic.gdx.utils.JsonReader()
                                  .parse("{\"center\":{\"insertion\":1.5}}"));
                            var error = assertThrows(IllegalArgumentException.class,
                                  () -> BuildingTurretVisual.assemble(library, invalid, state.structure()));
                            assertTrue(error.getMessage().contains("encased true/false"));
                        }
                        float length = module.descriptor().bounds().max().get(1);
                        var initial = new Vector3();
                        UnitModelAttachment.emitter(full.instance, emitter, initial, new Vector3());
                        for (float fraction : launcher ? new float[] { 0, .75f } : new float[] { 0, .4f, .7f, .88f }) {
                            var spec = new com.badlogic.gdx.utils.JsonReader().parse(recipe.toString());
                            var installations = new com.badlogic.gdx.utils.JsonValue(com.badlogic.gdx.utils.JsonValue.ValueType.object);
                            var center = new com.badlogic.gdx.utils.JsonValue(com.badlogic.gdx.utils.JsonValue.ValueType.object);
                            if (launcher) { center.addChild("encased", new com.badlogic.gdx.utils.JsonValue(fraction > 0)); }
                            else { center.addChild("insertion", new com.badlogic.gdx.utils.JsonValue(length * fraction)); }
                            installations.addChild("center", center); spec.addChild("installations", installations);
                            var model = BuildingTurretVisual.assemble(library, spec, state.structure()); models.add(model);
                            var tip = new Vector3();
                            UnitModelAttachment.emitter(model.instance, model.equipment().getFirst().emitters().getFirst(), tip, new Vector3());
                            var sourceBinding = full.equipment().getFirst();
                            var installedBinding = model.equipment().getFirst();
                            if (launcher) {
                                assertEquals(fraction == 0, roofMounted(model) == 1, "Exposed on the roof, or encased in the bay");
                                assertTrue(installedBinding.pitchOnly(), "One launcher pitches on its trunnion wherever it rides");
                                if (fraction == 0) {
                                    var hinge = model.instance.getNode(installedBinding.aimNode()).globalTransform.getTranslation(new Vector3());
                                    assertTrue(hinge.z > 18 && Math.abs(hinge.y) < 1, "External pod pivots above the roof centre");
                                    assertTrue(tip.z > 16 && tip.y < 5.1f, "Exposed pod is carried over the hull, not stuck on its nose");
                                } else {
                                    var bounds = library.modular(installedBinding.asset()).descriptor().bounds();
                                    var muzzle = installedBinding.emitters().getFirst();
                                    // Encased, only the launcher's front quarter stands out of the bay face.
                                    float insertion = bounds.max().get(1) - .25f * (bounds.max().get(1) - bounds.min().get(1));
                                    assertEquals(9.45f - insertion + muzzle.position().get(1), tip.y, .03f);
                                }
                            } else {
                                assertEquals(initial.y - length * fraction, tip.y, .03f, "Recessed emitter follows weapon without scaling");
                                assertEquals(initial.x, tip.x, .001f);
                                assertEquals(initial.z, tip.z, .001f);
                            }
                            assertEquals(sourceBinding.emitters().size(), installedBinding.emitters().size());
                            for (int cell = 0; cell < sourceBinding.emitters().size(); cell++) {
                                var originalCell = new Vector3(); var installedCell = new Vector3();
                                UnitModelAttachment.emitter(full.instance, sourceBinding.emitters().get(cell), originalCell, new Vector3());
                                UnitModelAttachment.emitter(model.instance, installedBinding.emitters().get(cell), installedCell, new Vector3());
                                assertTrue(originalCell.sub(initial).epsilonEquals(installedCell.sub(tip), .001f), "Cell spacing is invariant");
                            }
                            assertTrue(full.instance.getNode(sourceBinding.node()).scale
                                  .epsilonEquals(model.instance.getNode(installedBinding.node()).scale, .001f));
                            for (int lod = 0; lod < 2; lod++) {
                                var instance = new GpuUnitInstance(model); instance.bodyDetail(lod == 0 ? 100 : .01f, lod == 0);
                                String label = launcher ? example.label() + (fraction == 0 ? " | exposed" : " | encased 75%")
                                      : example.label() + " | inserted " + Math.round(fraction * 100) + "%";
                                (launcher ? launchers : exposures).get(lod).add(new View(label, instance, triangles(instance.nodes)));
                                var lodTip = new Vector3();
                                UnitModelAttachment.emitter(instance, installedBinding.emitters().getFirst(), lodTip, new Vector3());
                                assertTrue(lodTip.epsilonEquals(tip, .001f), "LOD keeps installation and launch position");
                            }
                        }
                    }
                    for (int lod = 0; lod < 2; lod++) {
                        palette(batch, exposures.get(lod), output, "turret-exposure", lod, false);
                        palette(batch, launchers.get(lod), output, "missile-installations", lod, false);
                    }
                    for (int lod = 0; lod < 2; lod++) {
                        var components = new ArrayList<View>();
                        var root = Configuration.dataDir().toPath().resolve("models/" + BuildingTurretVisual.ROOT);
                        try (var files = Files.list(root)) {
                            for (var file : files.filter(p -> p.toString().endsWith(".glb")).sorted().toList()) {
                                String name = file.getFileName().toString().replace(".glb", "");
                                var asset = library.modular(BuildingTurretVisual.ROOT + name + ".json");
                                var instance = new GpuUnitInstance(asset.model(lod));
                                components.add(new View(name, instance, triangles(instance.nodes)));
                            }
                        }
                        for (int start = 0; start < components.size(); start += 16) {
                            var page = components.subList(start, Math.min(start + 16, components.size()));
                            String name = start == 0 ? "weapon-components" : "weapon-components-page" + (start / 16 + 1);
                            palette(batch, page, output, name, lod, false);
                            palette(batch, page, output, name, lod, true);
                        }
                        var detailNames = List.of("autocannon-10", "laser-medium", "ppc", "srm-2", "srm-4", "lrm-20",
                              "mount-post", "mount-pedestal", "mount-arm");
                        palette(batch, components.stream().filter(v -> detailNames.contains(v.label())).toList(),
                              output, "weapon-components-detail", lod, false);
                    }
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    models.forEach(GpuUnitModel::dispose);
                    batch.dispose(); library.dispose(); Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Turret palette failed", failure.get()); }
    }

    /** Poses a fresh instance and gathers its pilot flames, the given seconds further into their flicker. */
    private static GpuUnitInstance pilots(GpuPilotFlames pilots, GpuUnitModel model, UnitModelState state, float seconds) {
        var instance = new GpuUnitInstance(model);
        var appearance = new BoardScene.UnitModel("", "", "", 1, 0, BoardScene.LocationDamage.NONE, state);
        var unit = new BoardScene.Unit(1, -1, "Turret", UnitPlaybackTest.unit(1, 0).location(), null, false, null, 2,
              false, appearance, 0);
        pilots.beginFrame(seconds);
        pilots.update(model, instance, unit);
        return instance;
    }

    /** Pilot flames close up at the nozzles, at night, as their tongues flicker. */
    private static void pilotPalette(ModelBatch batch, GpuPilotFlames pilots, GpuUnitModel model, UnitModelState state,
          Path output) {
        int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight(), cols = 3, rows = 2;
        int cw = w / cols, ch = h / rows;
        var env = new Environment();
        env.set(ColorAttribute.createAmbientLight(.12f, .12f, .14f, 1));
        env.add(new DirectionalLight().set(.25f, .25f, .3f, -.4f, -.7f, -1));
        var nozzles = new Vector3();
        int count = 0;
        var probe = new GpuUnitInstance(model);
        for (var binding : model.equipment()) {
            for (var emitter : binding.emitters()) {
                if (!"flame".equals(emitter.effect())) { continue; }
                var position = new Vector3();
                UnitModelAttachment.emitter(probe, emitter, position, new Vector3());
                nozzles.add(position); count++;
            }
        }
        nozzles.scl(1f / count);
        try (var surface = new GpuSceneTestOutput(w, h)) {
            surface.begin(.02f, .025f, .03f, 1);
            for (int i = 0; i < cols * rows; i++) {
                Gdx.gl.glDepthMask(true); Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
                Gdx.gl.glViewport((i % cols) * cw, (rows - 1 - i / cols) * ch, cw, ch);
                var instance = pilots(pilots, model, state, .09f);
                float width = i < cols ? 34 : 12;
                var camera = new OrthographicCamera(width, width * ch / (float) cw);
                camera.near = 1; camera.far = 1000;
                camera.position.set(nozzles).add(40, 70, 30); camera.up.set(Vector3.Z);
                camera.lookAt(nozzles); camera.update();
                batch.begin(camera); batch.render(instance, env); batch.end();
                pilots.render(camera);
            }
            Gdx.gl.glViewport(0, 0, w, h); surface.display();
            var pixels = Pixmap.createFromFrameBuffer(0, 0, w, h);
            try { PixmapIO.writePNG(new FileHandle(output.resolve("pilot-flames.png").toFile()), pixels, -1, true); }
            finally { pixels.dispose(); }
        }
    }

    /** The side-arm launchers on one side: each rides a bracket of its own, and a flank nothing rides has none. */
    private static long arms(GpuUnitModel model, String side) {
        return mounts(model.instance.nodes, "arm-" + side);
    }

    /** The roof weapons: each rides its own posts or pedestal, the first of which is its socket's mount 0. */
    private static long roofMounted(GpuUnitModel model) {
        return mounts(model.instance.nodes, "pod-");
    }

    /** Where a weapon pitches: its trunnion, or a pedestal's hinge. */
    private static Vector3 trunnion(GpuUnitModel model, UnitEquipmentAssembly.Binding binding) {
        return model.instance.getNode(binding.aimNode()).globalTransform.getTranslation(new Vector3());
    }

    private static long mounts(Iterable<Node> nodes, String socket) {
        long count = 0;
        for (var node : nodes) {
            count += (node.id.startsWith(socket) && node.id.endsWith("-mount-0") ? 1 : 0) + mounts(node.getChildren(), socket);
        }
        return count;
    }

    private static Fixture example(String label, String... weapons) throws Exception {
        var entity = new GunEmplacement();
        for (var name : weapons) {
            var type = EquipmentType.get(name);
            assertNotNull(type, name);
            entity.addEquipment(type, GunEmplacement.LOC_GUNS);
        }
        return new Fixture(label, entity);
    }

    private static int triangles(Iterable<Node> nodes) {
        int count = 0;
        for (var node : nodes) {
            for (var part : node.parts) { if (part.enabled) { count += part.meshPart.size / 3; } }
            count += triangles(node.getChildren());
        }
        return count;
    }

    private static void checkRecoil(GpuUnitModel model) {
        var posed = new GpuUnitInstance(model);
        for (var binding : model.equipment()) {
            if (binding.aimNode() == null) { continue; }
            var tip = binding.emitters().getFirst();
            var before = new Vector3(); var after = new Vector3();
            UnitModelAttachment.emitter(posed, tip, before, new Vector3());
            var mount = posed.getNode(binding.node());
            var rest = mount.translation.cpy();
            var collar = posed.getNode(binding.node() + "-collar");
            var collarRest = collar == null ? null : collar.globalTransform.cpy();
            UnitAnimator.applyWeaponRecoil(posed, model.instance, binding, 1);
            UnitModelAttachment.emitter(posed, tip, after, new Vector3());
            assertTrue(mount.translation.epsilonEquals(rest, .0001f), "The attachment must not recoil");
            if (collar != null) { assertArrayEquals(collarRest.val, collar.globalTransform.val, .0001f, "Receiver stays on its bearings"); }
            if (binding.recoilNode() == null) { assertTrue(after.epsilonEquals(before, .0001f)); }
            else {
                assertTrue(before.y > after.y + .1f);
                UnitAnimator.applyWeaponRecoil(posed, model.instance, binding, 0);
                UnitModelAttachment.emitter(posed, tip, after, new Vector3());
                assertTrue(after.epsilonEquals(before, .0001f), "Return to battery");
            }
        }
    }

    private static void checkRetainedYaw(GpuUnitModel model, UnitModelState state) {
        var source = UnitPlaybackTest.unit(1, 0);
        var appearance = new BoardScene.UnitModel("", "", "", 1, 0, BoardScene.LocationDamage.NONE, state);
        var unit = new BoardScene.Unit(1, -1, "Turret", source.location(), null, false, null, 2, false, appearance, 0);
        var placed = new GpuUnitInstance(model);
        var animator = new UnitAnimator();
        var motion = new UnitMotion(unit.location()).sample();
        animator.apply(model, placed, unit, motion, 0, 0, false, 0);
        var bearing = placed.getNode("yaw");
        var restForward = new Vector3(Vector3.Y).mul(bearing.rotation);
        var fixed = placed.getNode("mount").globalTransform.cpy();
        var attack = new UnitAttack(UnitPlaybackTest.attack(unit, UnitPlaybackTest.unit(2, 3), ResolvedAttack.Kind.SHOT, true));
        attack.seconds = UnitAttack.ANTICIPATION_SECONDS + UnitAttack.RECOIL_KICK_SECONDS;
        animator.aimShots(model, unit, List.of(attack), ignored -> null);
        var fired = bearing.rotation.cpy();
        for (var binding : model.equipment()) {
            if (!binding.pitchOnly()) { continue; }
            var aim = placed.getNode(binding.aimNode());
            assertTrue(new Vector3(Vector3.X).mul(aim.rotation).epsilonEquals(Vector3.X, .001f), "Trunnion remains aligned with its pins");
        }
        assertTrue(restForward.dot(new Vector3(Vector3.Y).mul(fired)) < .99f, "Bearing must turn toward the target");
        assertTrue(new Vector3(Vector3.Z).mul(fired).epsilonEquals(Vector3.Z, .001f), "Bearing may yaw, never pitch");
        assertArrayEquals(fixed.val, placed.getNode("mount").globalTransform.val, .001f, "Fixed collar stays attached");
        animator.apply(model, placed, unit, motion, 1, 1, false, 0);
        assertEquals(1, Math.abs(fired.dot(bearing.rotation)), .001f, "Keep last firing direction");
        var rebound = new GpuUnitInstance(model);
        rebound.bodyDetail(.01f, false);
        animator.apply(model, rebound, unit, motion, 2, 1, false, 0);
        assertEquals(1, Math.abs(fired.dot(rebound.getNode("yaw").rotation)), .001f, "Rebinding at LOD1 retains direction");
    }

    /** Casemates stand on their own facade at mid-floor, and turn no further than their weapons' 120-degree arc. */
    private static void checkCasemates(GpuUnitModel model, UnitModelState state) {
        var source = UnitPlaybackTest.unit(1, 0);
        var appearance = new BoardScene.UnitModel("", "", "", 1, 0, BoardScene.LocationDamage.NONE, state);
        var unit = new BoardScene.Unit(1, 0, "Fort", source.location(), null, false, null, 2, false, appearance, 0);
        var placed = new GpuUnitInstance(model);
        var animator = new UnitAnimator();
        animator.apply(model, placed, unit, new UnitMotion(unit.location()).sample(), 0, 0, false, 0);
        var ground = BoardGeometry.center(unit.location().coords(), 0);
        model.place(placed, new OrthographicCamera(), ground, 0, 1, false);
        int casemates = 0;
        for (var node : placed.nodes) {
            if (!node.id.startsWith("wall-")) { continue; }
            casemates++;
            String[] wall = node.id.split("-");
            float face = Integer.parseInt(wall[1]) * 60;
            var position = node.globalTransform.getTranslation(new Vector3()).mul(placed.transform).sub(ground);
            float along = position.x * MathUtils.cosDeg(face) - position.y * MathUtils.sinDeg(face);
            assertTrue(Math.abs(along) < BoardGeometry.height() / Math.sqrt(3) / 2, node.id + " stays on its face");
            if (face % 180 == 0) {
                // On the north and south faces the model's box is the casemate's: its back meets the facade, never inside.
                var box = node.extendBoundingBox(new BoundingBox().inf()).mul(placed.transform);
                float back = face == 0 ? box.min.y - ground.y : ground.y - box.max.y;
                assertTrue(back >= BoardGeometry.height() / 2 && back < BoardGeometry.height() / 2 + .5f,
                      node.id + " stands on its facade: " + back);
            }
            assertEquals((Integer.parseInt(wall[2]) + .5f - state.structure().building().height()) * BoardGeometry.level(),
                  position.z, .6f, node.id + " sits at the middle of its floor");
        }
        assertEquals(6, casemates, "Two twin casemates on each armed face");
        // A target behind the building: each casemate turns toward it only as far as its arc allows.
        var original = UnitPlaybackTest.attack(unit, UnitPlaybackTest.unit(2, 3), ResolvedAttack.Kind.SHOT, true);
        var result = original.result();
        for (var binding : model.equipment()) {
            if (!binding.node().startsWith("wall-")) { continue; }
            var shot = new UnitAttack(new BoardScene.Combat(new ResolvedAttack(result.id(), result.kind(), result.attacker(),
                  result.target(), result.targetType(), binding.index(), result.equipmentName(), result.limb(), true),
                  original.attacker(), original.target(), original.destination()));
            shot.seconds = UnitAttack.ANTICIPATION_SECONDS + UnitAttack.RECOIL_KICK_SECONDS;
            animator.aimShots(model, unit, List.of(shot), ignored -> null);
            String[] casemate = binding.node().split("-", 5);
            var yaw = placed.getNode(String.join("-", casemate[0], casemate[1], casemate[2], casemate[3], "yaw"));
            float angle = yaw.rotation.getAngleAround(Vector3.Z);
            angle = angle > 180 ? angle - 360 : angle;
            assertTrue(Math.abs(angle) > 1 && Math.abs(angle) <= UnitAnimator.CASEMATE_ARC + .5f,
                  binding.node() + " turned " + angle);
        }
    }

    private static void palette(ModelBatch batch, List<View> views, Path output, String name, int lod, boolean top) {
        int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        int cols = views.size() > 12 || name.equals("turret-exposure") ? 4 : 3;
        int rows = (views.size() + cols - 1) / cols;
        int cw = w / cols, ch = h / rows;
        var env = new Environment();
        env.set(ColorAttribute.createAmbientLight(.7f, .7f, .7f, 1));
        env.add(new DirectionalLight().set(.8f, .8f, .8f, -.4f, -.7f, -1));
        float width = top ? 78 : 52;
        var camera = new OrthographicCamera(width, width * ch / (float) cw);
        camera.near = 1; camera.far = 1000;
        try (var surface = new GpuSceneTestOutput(w, h)) {
            surface.begin(.16f, .18f, .20f, 1);
            for (int i = 0; i < views.size(); i++) {
                Gdx.gl.glDepthMask(true); Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
                Gdx.gl.glViewport((i % cols) * cw, (rows - 1 - i / cols) * ch, cw, ch);
                camera.position.set(top ? new Vector3(0, 0, 200) : new Vector3(60, name.endsWith("-rear") ? -100 : 100, 67));
                camera.up.set(top ? Vector3.Y : Vector3.Z);
                if (name.startsWith("weapon-components")) {
                    var bounds = views.get(i).instance().calculateBoundingBox(new com.badlogic.gdx.math.collision.BoundingBox());
                    var center = bounds.getCenter(new Vector3()); var size = bounds.getDimensions(new Vector3());
                    camera.viewportWidth = Math.max(6, Math.max(size.x, Math.max(size.y, size.z)) * 1.8f);
                    camera.viewportHeight = camera.viewportWidth * ch / (float) cw;
                    camera.position.add(center); camera.lookAt(center);
                } else if (name.startsWith("roof-mounts-close")) {
                    // Low from the right and a little ahead, close on the roof launcher and what carries it.
                    camera.viewportWidth = 24;
                    camera.viewportHeight = camera.viewportWidth * ch / (float) cw;
                    camera.position.set(90, 35, 24);
                    camera.lookAt(0, 0, 16);
                } else { camera.lookAt(0, 5, top ? 0 : 8); }
                camera.update();
                batch.begin(camera); batch.render(views.get(i).instance(), env); batch.end();
            }
            Gdx.gl.glViewport(0, 0, w, h); surface.display();
            var font = new BitmapFont(); var captions = new SpriteBatch();
            try {
                font.getData().setScale(1.3f);
                captions.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0, w, h));
                captions.begin();
                for (int i = 0; i < views.size(); i++) {
                    var view = views.get(i);
                    font.draw(captions, view.label() + " | LOD" + lod + " | " + view.triangles() + " tris",
                          (i % cols) * cw + 16, (rows - i / cols) * ch - 15);
                }
                captions.end();
            } finally { captions.dispose(); font.dispose(); }
            var pixels = Pixmap.createFromFrameBuffer(0, 0, w, h);
            try { PixmapIO.writePNG(new FileHandle(output.resolve(name + "-lod" + lod + (top ? "-top" : "") + ".png").toFile()), pixels, -1, true); }
            finally { pixels.dispose(); }
        }
    }
}
