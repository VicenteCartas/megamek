/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Long camera rays must pick flat roofs and modular roof trim without alternating with the last storey. */
@Tag("on-demand")
class GpuRoofHoverSmokeTest {
    private static final Coords CENTER = new Coords(4, 4);
    private static final String FLAT_ROOF = "buildings/standard/building-hard-square";

    @ParameterizedTest
    @ValueSource(strings = { FLAT_ROOF, GpuBuildingTest.ASSET })
    void hoveringAcrossARoofKeepsTheRoofSelected(String asset) {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1200, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { check(asset); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Roof pointer routing", failure.get()); }
    }

    private static void check(String asset) throws Exception {
        var scene = BoardScenes.scene(coords -> {
            var ground = BoardScenes.tile(coords, BoardScene.Surface.CONCRETE, 0, -1, 0);
            var features = coords.distance(CENTER) <= 1
                  ? List.of(new BoardScene.Feature(asset, 0, 0, 0, 1, coords.equals(CENTER) ? 5 : 4, 0,
                        BoardScene.FeatureKind.BUILDING)) : List.<BoardScene.Feature>of();
            return new BoardScene.Tile(coords, 0, -1, false, 0, ground.surface(), null,
                  null, features, List.of(), BoardLiquid.NONE, null, true);
        });
        var terrain = new GpuTerrain();
        var view = new GpuBattleView(mock(BoardSource.class));
        set(view, "scene", scene);
        set(view, "terrain", terrain);
        set(view, "ui", mock(GpuBoardHud.class));
        set(view, "markers", mock(GpuMarkers.class));
        set(view, "tuning", mock(GpuBoardTuning.class));
        try {
            terrain.update(scene);
            var camera = view.boardCamera;
            camera.resize(1200, 900);
            camera.setIsometric(true);
            camera.camera.zoom = .4f;
            camera.center(BoardGeometry.center(CENTER, 5));
            for (boolean perspective : List.of(false, true)) {
                camera.setPerspective(perspective);
                sweepRoof(view, terrain, scene);
            }
        } finally { view.dispose(); }
    }

    private static void sweepRoof(GpuBattleView view, GpuTerrain terrain, BoardScene scene) throws Exception {
        var camera = view.boardCamera.camera;
        var bounds = terrain.roofBounds(CENTER);
        var input = (InputProcessor) field(view, "boardInput");
        float roofZ = 5 * BoardGeometry.level();
        int checked = 0, wrong = 0;
        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        for (int dx = 1; dx < 12; dx++) {
            for (int dy = 1; dy < 12; dy++) {
                var ray = new Ray(new Vector3(bounds.min.x + bounds.getWidth() * dx / 12,
                      bounds.min.y + bounds.getHeight() * dy / 12, bounds.max.z + 100), new Vector3(0, 0, -1));
                var roof = terrain.selectionHit(scene, ray);
                if (roof == null || !CENTER.equals(roof.coords())) { continue; }
                var point = ray.getEndPoint(new Vector3(), (float) Math.sqrt(roof.distance()));
                if (point.z < roofZ - .02f) { continue; }
                camera.project(point, 0, 0, camera.viewportWidth, camera.viewportHeight);
                int x = Math.round(point.x), y = Gdx.graphics.getHeight() - Math.round(point.y);
                ray = camera.getPickRay(x, y, 0, 0, camera.viewportWidth, camera.viewportHeight);
                var hit = terrain.selectionHit(scene, ray);
                if (hit == null || !CENTER.equals(hit.coords())) { continue; }
                float z = ray.getEndPoint(new Vector3(), (float) Math.sqrt(hit.distance())).z;
                if (z < roofZ - .02f) { continue; }
                input.mouseMoved(x, y);
                assertEquals(CENTER, field(view, "hovered"));
                low = Math.min(low, (float) field(view, "hoverZ"));
                high = Math.max(high, (float) field(view, "hoverZ"));
                checked++;
                if (GpuLosResult.pointedLevel((float) field(view, "hoverZ")) * BoardGeometry.level() != roofZ) { wrong++; }
            }
        }
        assertTrue(checked > 20, "Sweep must cover the visible roof");
        assertEquals(0, wrong, "Every roof point must select the roof, hit range=" + low + ".." + high);
    }

    private static void set(GpuBattleView view, String name, Object value) throws Exception {
        var field = GpuBattleView.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(view, value);
    }
}
