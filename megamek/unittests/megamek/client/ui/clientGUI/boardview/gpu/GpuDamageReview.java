/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuCamouflageReview.parts;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuCamouflageReview.renderReady;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.IArmorState;
import megamek.common.units.BipedMek;
import megamek.common.units.Mek;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/** Damage materials and collectable limb props through the real asset library, Swing snapshots and board renderer. */
final class GpuDamageReview {
    private GpuDamageReview() { }

    static void verify(GpuUnitModels library) throws Exception {
        verifyModels(library);
        groundRemains(library);
    }

    /** Asset/material behavior can be reviewed independently of terrain rebuilding and collectable prop placement. */
    static void verifyModels(GpuUnitModels library) throws Exception {
        var damage = new UnitDamageDisplay();
        var batch = new ModelBatch(GpuUnitShader.provider());
        try {
            var mek = new BipedMek();
            for (int location = 0; location < mek.locations(); location++) {
                mek.initializeInternal(10, location);
            }
            mek.addEquipment(EquipmentType.get("PPC"), Mek.LOC_LEFT_ARM);
            mek.addEquipment(EquipmentType.get("PPC"), Mek.LOC_RIGHT_ARM);
            var model = library.get(UnitCamouflageTest.selection(mek), 6300);
            var intact = new ModelInstance(model.instance.model);
            var old = new ModelInstance(model.instance.model);
            var wrecked = new ModelInstance(model.instance.model);
            var locations = new BoardScene.LocationDamage(Set.of(), Set.of("RT", "LA", "RL"));
            UnitDamageDisplay.show(old, locations);
            for (var part : parts(old.nodes)) {
                if (part.material.id.endsWith(UnitDamageDisplay.WRECKED_SUFFIX)) {
                    part.material.set(ColorAttribute.createDiffuse(GpuColor.linear(.13f, .12f, .11f)));
                }
            }
            UnitDamageDisplay.show(wrecked, locations);
            damage.applyTexture(wrecked);
            var texture = texture(UnitDamageDisplay.locationParts(wrecked, "RL").getFirst());
            assertNotNull(texture);
            assertEquals(128, texture.getWidth());
            assertEquals(128, texture.getHeight());
            var pixels = new Pixmap(new FileHandle(new File(Configuration.dataDir(), "models/units/textures/destroyed-armor.png")));
            try {
                Set<Integer> grays = new java.util.HashSet<>();
                for (int y = 0; y < pixels.getHeight(); y++) {
                    for (int x = 0; x < pixels.getWidth(); x++) {
                        int rgba = pixels.getPixel(x, y);
                        int gray = rgba >>> 24;
                        assertTrue(gray < 150);
                        assertEquals(gray, (rgba >>> 16) & 255);
                        assertEquals(gray, (rgba >>> 8) & 255);
                        grays.add(gray);
                    }
                }
                assertTrue(grays.size() > 16, "Scorch artwork must retain visible tonal variation");
            } finally {
                pixels.dispose();
            }
            for (var part : UnitDamageDisplay.locationParts(wrecked, "LA")) {
                assertSame(texture, texture(part), "Attached weapons and anatomy share the damage texture");
                assertTrue(part.material.has(GpuLinearColors.TYPE));
                assertEquals(GpuColor.linear(UnitDamageDisplay.WRECKED),
                      part.material.get(ColorAttribute.class, ColorAttribute.Diffuse).color);
                assertFalse(part.material.has(ColorAttribute.Emissive));
            }
            assertTrue(parts(intact.nodes).stream().allMatch(part -> part.enabled && texture(part) == null));
            old.transform.setToTranslation(-43, 0, 0);
            wrecked.transform.setToTranslation(43, 0, 0);
            GpuModularUnitModelsSmokeTest.renderReview(batch, List.of(old, wrecked), "runtime-damage-before-after", 190, 20);

            for (int location : new int[] { Mek.LOC_HEAD, Mek.LOC_LEFT_ARM, Mek.LOC_RIGHT_LEG }) {
                mek.destroyLocation(location, true);
            }
            var detached = new ModelInstance(model.instance.model);
            UnitDamageDisplay.show(detached, UnitModelSelection.damage(mek));
            for (String location : List.of("HD", "LA", "RL")) {
                assertTrue(UnitDamageDisplay.locationParts(detached, location).stream().noneMatch(part -> part.enabled),
                      "A blown-off location hides all owned anatomy and equipment: " + location);
            }
            assertTrue(UnitDamageDisplay.locationParts(detached, "RA").stream().allMatch(part -> part.enabled));
            detached.transform.setToTranslation(43, 0, 0);
            intact.transform.setToTranslation(-43, 0, 0);
            GpuModularUnitModelsSmokeTest.renderReview(batch, List.of(intact, detached), "runtime-blown-off-locations", 190, 20);
            int handle = texture.getTextureObjectHandle();
            damage.dispose();
            assertFalse(Gdx.gl.glIsTexture(handle), "The view releases its shared damage texture");
        } finally {
            damage.dispose();
            batch.dispose();
        }
    }

    private static Texture texture(NodePart part) {
        var overlay = part.material.get(UnitDamageDisplay.Overlay.class, UnitDamageDisplay.Overlay.TYPE);
        return overlay == null ? null : overlay.texture;
    }

    private static void groundRemains(GpuUnitModels library) throws Exception {
        var coords = new Coords(5, 5);
        try (var fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Hex hex = new Hex(2);
                hex.addTerrain(new Terrain(Terrains.ARMS, 2));
                hex.addTerrain(new Terrain(Terrains.LEGS, 1));
                fixture.game.getBoard().setHex(coords, hex);
                fixture.entity.destroyLocation(Mek.LOC_LEFT_ARM, true);
                fixture.entity.setInternal(IArmorState.ARMOR_DESTROYED, Mek.LOC_RIGHT_LEG);
                fixture.source.refresh();
                // Raising the supporting hex queues a displacement; this review starts at the final setup state.
                fixture.source.takeFrame();
            });
            var view = new GpuBattleView(fixture.source);
            try {
                view.create();
                GpuBoardTestUi.<SelectBox<UnitDisplayMode>>tuning(view, "tuning-unit-display")
                      .setSelected(UnitDisplayMode.MODELS);
                renderReady(view);
                var terrain = (GpuTerrain) field(view, "terrain");
                var unit = (ModelInstance) ((Map<?, ?>) field(view, "unitInstances")).get("1:-1");
                assertFalse(UnitDamageDisplay.locationParts(unit, "LA").isEmpty());
                assertFalse(UnitDamageDisplay.locationParts(unit, "RL").isEmpty());
                assertTrue(UnitDamageDisplay.locationParts(unit, "LA").stream().noneMatch(part -> part.enabled));
                assertTrue(UnitDamageDisplay.locationParts(unit, "RL").stream()
                      .allMatch(part -> texture(part) != null && texture(part).getWidth() == 128));
                var scene = (BoardScene) field(view, "scene");
                var tile = scene.tile(coords);
                assertNotNull(tile.decals(), "The 2D limb marker remains available as fallback");
                assertNotNull(tile.decalsWithoutLimbs(), "An empty replacement decal is retained, not mistaken for unavailable");
                assertNotEquals(tile.decals(), tile.decalsWithoutLimbs());
                var models = salvageModels(terrain);
                var props = limbInstances(terrain, models);
                assertEquals(3, props.size());
                assertEquals(2, props.stream().filter(prop -> prop.model == models.get(0)).count());
                assertEquals(1, props.stream().filter(prop -> prop.model == models.get(1)).count());
                for (var prop : props) {
                    // A limb rests just above the plateau's sculpted top, which undulates within its natural relief.
                    assertEquals(2 * BoardGeometry.LEVEL + .12f * BoardGeometry.HEX_SCALE,
                          UnitBounds.world(prop).min.z, BoardRelief.metres(.5f));
                }
                assertSame(tile.decalsWithoutLimbs(), selectedDecals(terrain, coords));
                view.boardCamera.center(BoardGeometry.center(coords, 2));
                view.boardCamera.zoom(.16f);
                capture(view, "runtime-limb-remains");
                // Salvage belongs to the board asset library, including when no unit library is available.
                var standalone = new GpuTerrain();
                var missingLibrary = new GpuUnitModels(Configuration.dataDir().toPath().resolve("missing-models"));
                var missing = new GpuTerrain(missingLibrary);
                try {
                    standalone.update(scene);
                    missing.update(scene);
                    assertEquals(3, limbInstances(standalone, salvageModels(standalone)).size());
                    assertEquals(3, limbInstances(missing, salvageModels(missing)).size());
                    assertSame(tile.decalsWithoutLimbs(), selectedDecals(standalone, coords));
                    assertSame(tile.decalsWithoutLimbs(), selectedDecals(missing, coords));
                } finally {
                    standalone.dispose();
                    missing.dispose();
                    missingLibrary.dispose();
                }
                SwingUtilities.invokeAndWait(() -> {
                    Hex hex = fixture.game.getBoard().getHex(coords).duplicate();
                    hex.addTerrain(new Terrain(Terrains.ARMS, 1));
                    fixture.game.getBoard().setHex(coords, hex);
                    fixture.source.refresh();
                });
                renderReady(view);
                var remaining = limbInstances(terrain, models);
                assertEquals(2, remaining.size());
                assertTrue(remaining.stream().allMatch(after -> props.stream()
                      .anyMatch(before -> samePlacement(before, after))),
                      () -> "Picking up a limb preserves the other placements: before="
                            + props.stream().map(prop -> java.util.Arrays.toString(prop.transform.val)).toList()
                            + ", after=" + remaining.stream().map(prop -> java.util.Arrays.toString(prop.transform.val)).toList());
                SwingUtilities.invokeAndWait(() -> {
                    fixture.game.getBoard().setHex(coords, new Hex(2));
                    fixture.source.refresh();
                });
                renderReady(view);
                assertTrue(limbInstances(terrain, models).isEmpty());
                for (Model model : models) {
                    assertTrue(parts(new ModelInstance(model).nodes).stream().allMatch(part -> part.enabled),
                          "Collecting salvage must not mutate its shared board model");
                }
                assertTrue(parts(new ModelInstance(library.equipment("Limb Club").model()).nodes)
                      .stream().allMatch(part -> part.enabled), "Terrain must not alter shared equipment");
            } finally {
                view.dispose();
            }
        }
    }

    private static boolean samePlacement(ModelInstance before, ModelInstance after) {
        for (int i = 0; i < before.transform.val.length; i++) {
            // Equivalent terrain triangles can round ground height by one ULP; XY, rotation and scale stay exact.
            float tolerance = i == Matrix4.M23 ? .00001f : 0;
            if (!MathUtils.isEqual(before.transform.val[i], after.transform.val[i], tolerance)) { return false; }
        }
        return true;
    }

    private static BoardScene.Pixels selectedDecals(GpuTerrain terrain, Coords coords) throws Exception {
        for (var entry : ((Map<?, ?>) field(terrain, "decalPixels")).entrySet()) {
            if (coords.equals(field(entry.getKey(), "coords")) && (int) field(entry.getKey(), "kind") == 0) {
                return (BoardScene.Pixels) entry.getValue();
            }
        }
        return null;
    }

    private static List<Model> salvageModels(GpuTerrain terrain) throws Exception {
        var assets = (GpuAssets) field(terrain, "assets");
        return List.of(assets.model("salvage/arm"), assets.model("salvage/leg"));
    }

    private static List<ModelInstance> limbInstances(GpuTerrain terrain, List<Model> models) throws Exception {
        var result = new ArrayList<ModelInstance>();
        for (Object chunk : (List<?>) field(terrain, "chunks")) {
            for (Object prop : (List<?>) field(chunk, "props")) {
                var instance = (ModelInstance) field(prop, "instance");
                if (models.contains(instance.model)) {
                    result.add(instance);
                }
            }
        }
        return result;
    }

    private static void capture(GpuBattleView view, String name) throws Exception {
        for (boolean top : new boolean[] { false, true }) {
            view.boardCamera.setIsometric(!top);
            renderReady(view);
            var image = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            try {
                PixmapIO.writePNG(new FileHandle(new File(System.getProperty("megamek.gpu.screenshots"),
                      name + "-" + (top ? "top" : "isometric") + ".png")), image, -1, true);
            } finally {
                image.dispose();
            }
        }
    }
}
