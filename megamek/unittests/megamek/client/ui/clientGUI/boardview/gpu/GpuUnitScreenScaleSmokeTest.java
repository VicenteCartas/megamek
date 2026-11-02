/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuCamouflageReview.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.gdx.DisplayScale;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Zoom-out unit scaling in the running 3D view and on the board-space harness: at or above the
 * threshold a unit keeps its size; zoomed out further it grows on every axis and keeps its size on screen, up to the
 * maximum; picking and its anchor follow the grown unit, switching it off restores the size, and the Tactical View's
 * icons keep their size in the hex.
 */
@Tag("on-demand")
class GpuUnitScreenScaleSmokeTest {
    /** The fixture's own Atlas. */
    private static final int ATLAS = 1;
    private static final Coords OWN = new Coords(5, 5);
    private static final float NEAR = .0001f;

    @Test
    void zoomedOutUnitsGrowUniformlyKeepTheirSizeOnScreenAndStayPickable() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        GUIPreferences preferences = GUIPreferences.getInstance();
        float originalScale = preferences.getBoardUiScale();
        try (var fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.setVisibleArea(new Rectangle(0, 0, 16, 17));
                fixture.source.refresh();
            });
            var configuration = GpuBoardWindow.configuration(false);
            configuration.setWindowedMode(1920, 1080);
            configuration.setInitialVisible(false);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    GpuBattleView view = null;
                    try {
                        // One logical HUD unit per window pixel, as in the 1920x1080 prototype captures.
                        float preference = .1f / DisplayScale.read(.1f, new GpuDisplayScale().contentScale());
                        SwingUtilities.invokeAndWait(() -> preferences.setBoardUiScale(preference));
                        view = new GpuBattleView(fixture.source);
                        view.create();
                        // The board arrives once its terrain is built behind the loading screen.
                        GpuBoardTestUi.present(view);
                        verify(view);
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        if (view != null) { view.dispose(); }
                        Gdx.app.exit();
                    }
                }
            }, configuration);
        } finally {
            SwingUtilities.invokeAndWait(() -> preferences.setBoardUiScale(originalScale));
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    @SuppressWarnings("unchecked")
    private static void verify(GpuBattleView view) throws Exception {
        render(view);
        var instances = (Map<String, ModelInstance>) field(view, "unitInstances");
        var scene = (BoardScene) field(view, "scene");
        ModelInstance model = instances.get(ATLAS + ":-1");
        assertNotNull(model, "The own Atlas is a 3D model");
        // The board view's tuning model, whose controls the panel mirrors; the growth on, from its constants.
        CheckBox scaling = GpuBoardTestUi.tuning(view, "tuning-zoom-scaling");
        scaling.setChecked(true);
        float threshold = UnitScreenScale.THRESHOLD;
        float scale = (float) field(view, "layoutScale");
        view.boardCamera.center(BoardGeometry.center(OWN, scene.tile(OWN).elevation()));

        // At and above the threshold the Atlas keeps its size.
        zoomTo(view, 2 * threshold, scale);
        Vector3 size = model.transform.getScale(new Vector3());
        zoomTo(view, threshold, scale);
        assertEquals(size, model.transform.getScale(new Vector3()), "At the threshold the Atlas keeps its size");
        Vector3 feet = model.transform.getTranslation(new Vector3());
        float tall = screenHeight(view, model);
        float head = screen(view, feet).y - screen(view, anchor(view)).y;
        System.out.printf("Hex %.0f HUD pixels wide: the Atlas is %.1f pixels tall, its anchor %.1f above its feet%n",
              threshold, tall, head);
        capture("unit-scale-threshold.png");

        // Zoomed out, it grows on every axis about its feet and keeps the size it had on screen, up to the maximum.
        for (float hexPixels : new float[] { threshold / 1.25f, threshold / 1.5f, threshold / UnitScreenScale.MAX,
              threshold / 3 }) {
            zoomTo(view, hexPixels, scale);
            float growth = Math.min(threshold / hexPixels, UnitScreenScale.MAX);
            Vector3 grown = model.transform.getScale(new Vector3());
            System.out.printf("Hex %.1f HUD pixels wide: the Atlas grows %.3f times and is %.1f pixels tall%n",
                  hexPixels, grown.x / size.x, screenHeight(view, model));
            assertEquals(growth * size.x, grown.x, NEAR * size.x, "Grown " + growth + " times");
            assertEquals(growth * size.y, grown.y, NEAR * size.y);
            assertEquals(growth * size.z, grown.z, NEAR * size.z, "on every axis: its proportions stay");
            assertTrue(feet.epsilonEquals(model.transform.getTranslation(new Vector3()), .001f), "about its feet");
            float onScreen = growth * hexPixels / threshold;
            assertEquals(onScreen * tall, screenHeight(view, model), .02f * tall, "As tall on screen as at the "
                  + "threshold until the maximum, then shrinking with the hex");
            assertEquals(onScreen * head, screen(view, feet).y - screen(view, anchor(view)).y, .02f * head,
                  "Its anchor, where labels hang, follows");
        }

        // Click the grown mesh outside the normal model's projected bounds and outline, then switch growth off.
        zoomTo(view, threshold / UnitScreenScale.MAX, scale);
        capture("unit-scale-zoomed-out.png");
        Vector3 grownPoint = grownPoint(view, model);
        assertTrue(picksModel(view, grownPoint), "The grown Atlas takes the pick outside its normal bounds");
        scaling.setChecked(false);
        render(view);
        assertEquals(size, model.transform.getScale(new Vector3()), "Switched off, the Atlas keeps its size");
        capture("unit-scale-zoomed-out-off.png");
        assertFalse(picksModel(view, grownPoint), "At its size the Atlas is not under that point");
        scaling.setChecked(true);

        // The Tactical View's units keep their share of the hex where the 3D view's units grow: the base spans its
        // hex at every zoom.
        zoomTo(view, threshold / 3, scale);
        GpuBoardTestUi.press(KeyCommandBind.TOGGLE_ISO);
        render(view);
        assertTrue(view.boardCamera.tactical());
        var tacticalUnit = scene.units().stream().filter(unit -> unit.id() == ATLAS).findFirst().orElseThrow();
        ModelInstance base = ((GpuUnitBases) field(view, "unitBases")).base(tacticalUnit);
        assertEquals(BoardGeometry.height() / 2, base.transform.getScaleX(), .001f,
              "The base is sized in apothems of the hex, without growth");
        GpuBoardTestUi.press(KeyCommandBind.TOGGLE_ISO);
        render(view);
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
    }

    @Test
    void theBoardSpaceHarnessGrowsItsUnitsAsTheViewDoesAndPickingHitsTheGrownUnit() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene);
            UnitPicking picking = new UnitPicking();
            try {
                UnitScreenScale.enabled = true;
                UnitScreenScale.threshold = UnitScreenScale.THRESHOLD;
                UnitScreenScale.max = UnitScreenScale.MAX;
                board.view(false);
                var atlas = scene.units().stream().filter(unit -> unit.id() == GpuHudFixtures.ATLAS).findFirst()
                      .orElseThrow();
                BoardProjectionCamera camera = board.camera.camera;
                board.camera.center(board.poses.get(atlas).position());
                board.camera.zoom(BoardGeometry.WIDTH / (UnitScreenScale.THRESHOLD / 1.5f) / camera.zoom);
                board.draw(view -> { });
                ModelInstance sprite = board.instances.get(atlas);
                Vector3 centre = sprite.transform.getTranslation(new Vector3());
                float anchor = board.anchors.get(atlas).dst(centre);
                hud.captureBackBuffer("unit-scale-harness").dispose();
                // A point of the grown sprite .45 of its width from its centre: .675 of its width at its size.
                Vector3 edge = new Vector3(.45f * atlas.image().width(), 0, 0).mul(sprite.transform);
                Ray ray = new Ray(new Vector3(edge).mulAdd(camera.direction, -10000), camera.direction);
                assertTrue(picking.distance(sprite, ray) < Float.POSITIVE_INFINITY, "The grown sprite takes the pick");

                UnitScreenScale.enabled = false;
                board.draw(view -> { });
                assertEquals(Float.POSITIVE_INFINITY, picking.distance(sprite, ray), "At its size, it does not");
                assertEquals(anchor, 1.5f * board.anchors.get(atlas).dst(centre), .01f * anchor,
                      "The harness's anchor follows the growth, as the view's does");
            } finally {
                UnitScreenScale.enabled = UnitScreenScale.ENABLED;
                board.dispose();
            }
        });
    }

    /** Zooms the 3D view so that a hex is {@code hexPixels} HUD pixels wide, then renders. */
    private static void zoomTo(GpuBattleView view, float hexPixels, float scale) {
        view.boardCamera.zoom(BoardGeometry.WIDTH / (hexPixels * scale) / view.boardCamera.camera.zoom);
        render(view);
    }

    /** The Atlas's anchor in the view's last frame. */
    @SuppressWarnings("unchecked")
    private static Vector3 anchor(GpuBattleView view) throws Exception {
        var anchors = (Map<BoardScene.Unit, Vector3>) field(view, "unitAnchors");
        return anchors.entrySet().stream().filter(entry -> entry.getKey().id() == ATLAS).findFirst().orElseThrow()
              .getValue();
    }

    /** The height on screen, in window pixels, of the instance's posed bounds. */
    private static float screenHeight(GpuBattleView view, ModelInstance instance) throws Exception {
        return screenBounds(view, instance).height;
    }

    private static com.badlogic.gdx.math.Rectangle screenBounds(GpuBattleView view, ModelInstance instance) {
        BoundingBox bounds = UnitBounds.world(instance);
        float left = Float.POSITIVE_INFINITY, top = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            Vector3 point = screen(view, new Vector3((corner & 1) == 0 ? bounds.min.x : bounds.max.x,
                  (corner & 2) == 0 ? bounds.min.y : bounds.max.y, (corner & 4) == 0 ? bounds.min.z : bounds.max.z));
            left = Math.min(left, point.x); right = Math.max(right, point.x);
            top = Math.min(top, point.y); bottom = Math.max(bottom, point.y);
        }
        return new com.badlogic.gdx.math.Rectangle(left, top, right - left, bottom - top);
    }

    /** A real pointer pixel on the grown mesh, clear of the normal mesh and its clickable outline. */
    private static Vector3 grownPoint(GpuBattleView view, ModelInstance model) throws Exception {
        var normal = new ModelInstance(model);
        normal.transform.scale(1 / UnitScreenScale.MAX, 1 / UnitScreenScale.MAX, 1 / UnitScreenScale.MAX);
        var excluded = screenBounds(view, normal);
        float gap = 2 * GpuUnitVisibility.OUTLINE_STEP * (float) field(view, "layoutScale") + 2;
        excluded.set(excluded.x - gap, excluded.y - gap, excluded.width + 2 * gap, excluded.height + 2 * gap);
        var grown = screenBounds(view, model);
        var picking = new UnitPicking();
        var camera = view.boardCamera.camera;
        for (int y = (int) Math.ceil(grown.y); y <= grown.y + grown.height; y++) {
            for (int x = (int) Math.ceil(grown.x); x <= grown.x + grown.width; x++) {
                if (!excluded.contains(x, y) && Float.isFinite(picking.distance(model, camera.getPickRay(x, y, 0, 0,
                      camera.viewportWidth, camera.viewportHeight)))) {
                    return new Vector3(x, y, 0);
                }
            }
        }
        throw new AssertionError("The grown model must extend beyond the normal model and outline");
    }

    /** Window coordinates (y down) of a world point. */
    private static Vector3 screen(GpuBattleView view, Vector3 world) {
        BoardProjectionCamera camera = view.boardCamera.camera;
        Vector3 point = camera.project(world.cpy(), 0, 0, camera.viewportWidth, camera.viewportHeight);
        point.y = Gdx.graphics.getHeight() - point.y;
        return point;
    }

    /** Distinguishes a direct model hit from a terrain hit, even when both return the unit's hex. */
    private static boolean picksModel(GpuBattleView view, Vector3 point) throws Exception {
        Object input = field(view, "boardInput");
        var pick = input.getClass().getDeclaredMethod("pickSelection", int.class, int.class);
        pick.setAccessible(true);
        Object picked = pick.invoke(input, Math.round(point.x), Math.round(point.y));
        var entityId = picked.getClass().getDeclaredMethod("entityId");
        var onTerrain = picked.getClass().getDeclaredMethod("onTerrain");
        entityId.setAccessible(true);
        onTerrain.setAccessible(true);
        return (int) entityId.invoke(picked) == ATLAS && !(boolean) onTerrain.invoke(picked);
    }

    private static void render(GpuBattleView view) {
        for (int frame = 0; frame < 3; frame++) {
            view.render();
        }
    }

    private static void capture(String name) {
        File directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(directory.isDirectory() || directory.mkdirs());
        GpuBoardTestUi.capture(new File(directory, name));
    }
}
