/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import megamek.common.board.Coords;
import megamek.common.loaders.MekFileParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native rendering, live selection, shared picking, and asset ownership across all three display modes. */
@Tag("on-demand")
class GpuMeepleSmokeTest {
    @Test
    void switchesMixedUnitsInBothCamerasAndRestoresTheDefault() throws Exception {
        var failure = new AtomicReference<Throwable>();
        try (var fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    int id = 2;
                    for (String file : List.of("Bulldog Medium Tank.blk", "Chippewa CHP-W7.blk",
                          "Elemental BA [Laser] (Sqd5).blk", "Foot Platoon (AFFS) (Laser 3067+).blk")) {
                        var entity = new MekFileParser(new File("testresources/megamek/common/units", file)).getEntity();
                        entity.setId(id++);
                        entity.setPosition(new Coords(id + 3, 5));
                        entity.setOwner(fixture.player);
                        entity.setDeployed(true);
                        fixture.game.addEntity(entity, false);
                    }
                    fixture.source.refresh();
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                int frame;
                boolean started;

                @Override
                public void render() {
                    try {
                        if (GpuBoardTestUi.loading(this)) { super.render(); return; }
                        var tuning = GpuBoardTestUi.tuning(this);
                        SelectBox<UnitDisplayMode> choice = GpuBoardTestUi.tuning(tuning, "tuning-unit-display");
                        // A frame may repeat while a display change loads; only the first one sees the default.
                        if (!started) {
                            started = true;
                            assertEquals(UnitDisplayMode.DEFAULT, choice.getSelected());
                            checkContourPicking(boardCamera);
                        }
                        var expected = frame == 6 ? UnitDisplayMode.DEFAULT : switch (frame % 3) {
                            case 0 -> UnitDisplayMode.MEK_MEEPLES;
                            case 1 -> UnitDisplayMode.ALL_MEEPLES;
                            default -> UnitDisplayMode.MODELS;
                        };
                        if (frame == 6) {
                            TextButton defaults = tuning.defaults();
                            defaults.fire(new ChangeListener.ChangeEvent());
                        } else { choice.setSelected(expected); }
                        boardCamera.setIsometric(frame >= 3);
                        if (frame == 3) {
                            boardCamera.center(BoardGeometry.center(new Coords(7, 5), 0));
                            boardCamera.zoom(.3f);
                        }
                        super.render();
                        if (GpuBoardTestUi.loading(this)) { return; }
                        assertEquals(expected, tuning.unitDisplayMode());
                        var scene = (BoardScene) field(this, "scene");
                        var library = (GpuUnitModels) field(this, "unitModels");
                        @SuppressWarnings("unchecked")
                        var instances = (Map<String, ModelInstance>) field(this, "unitInstances");
                        assertEquals(5, scene.units().size());
                        for (var unit : scene.units()) {
                            var instance = instances.get(unit.id() + ":" + unit.part());
                            assertNotNull(instance);
                            // The battle armor (4) and the platoon (5) stand as meeple formations instead of tokens.
                            boolean troops = unit.id() >= 4;
                            boolean token = expected == UnitDisplayMode.ALL_MEEPLES && !troops
                                  || (expected == UnitDisplayMode.MEK_MEEPLES && unit.id() == 1);
                            var authored = library.loaded(unit.model(), unit.id());
                            if (token) {
                                assertNull(authored, "Effects must not reuse the hidden authored assembly");
                                boolean cutout = false;
                                for (var part : instance.model.meshParts) { cutout |= "cutout".equals(part.id); }
                                assertTrue(cutout);
                                assertTrue(UnitBounds.world(instance).getDepth() > 0);
                                if (!unit.airborne()) {
                                    assertEquals(unit.location().elevation() * BoardGeometry.level() + .5f,
                                          UnitBounds.world(instance).min.z, .001f,
                                          "Scenery cannot lift the Meeple above its captured hex elevation");
                                    assertEquals(1, new Vector3(Vector3.Z).rot(instance.transform).nor().z, .001f,
                                          "Standing Meeples remain flat in both cameras");
                                }
                            } else {
                                assertNotNull(authored);
                                assertSame(authored.instance.model, instance.model);
                                assertEquals(troops && expected == UnitDisplayMode.ALL_MEEPLES,
                                      authored.rigs().stream().anyMatch(UnitRig::meeple), "Each display keeps its own figures");
                            }
                            assertTrue(UnitBounds.world(instance).isValid());
                        }
                        if (frame >= 3 && frame <= 5) {
                            GpuBoardTestUi.capture(new File(System.getProperty("megamek.gpu.screenshots",
                                  "build/gpu-board-review"), "meeple-" + expected.name() + ".png"));
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        if (++frame > 6) { Gdx.app.exit(); }
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, GpuBoardWindow.configuration(false));
            assertEquals(0, fixture.clicks.get());
        }
        if (failure.get() != null) { throw new AssertionError("Meeple rendering failed", failure.get()); }
    }

    private static void checkContourPicking(BoardCamera camera) {
        var image = new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB);
        for (int y = 2; y < 10; y++) {
            for (int x = 2; x < 10; x++) { image.setRGB(x, y, 0xff338844); }
        }
        // A transparent hole must stay unpickable through both caps; walls must be pickable from the side.
        for (int y = 5; y < 7; y++) {
            for (int x = 5; x < 7; x++) { image.setRGB(x, y, 0); }
        }
        var pixels = new BoardScene.Pixels(image);
        var textures = new GpuTextures<BoardScene.Pixels>();
        textures.update(Map.of(pixels, pixels));
        var token = GpuUnitModel.meeple(pixels, textures.region(pixels));
        try {
            var picking = new UnitPicking();
            token.place(token.instance, camera.camera, new Vector3(), 0, 2, false);
            assertTrue(Float.isInfinite(picking.distance(token.instance,
                  new Ray(new Vector3(0, 0, 100), new Vector3(0, 0, -1)))));
            assertTrue(Float.isFinite(picking.distance(token.instance,
                  new Ray(new Vector3(-100, 0, BoardGeometry.level()), Vector3.X))));
            assertEquals(2 * BoardGeometry.level() * BoardGeometry.unitHeightScale(),
                  UnitBounds.world(token.instance).getDepth(), .001f);
        } finally { token.dispose(); textures.dispose(); }
    }
}
