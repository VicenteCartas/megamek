/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Close views of exposed earth, separating mesh shading from color and mapped relief. */
@Tag("on-demand")
class GpuDirtMaterialsSmokeTest {
    @Test
    void drawsEarthBanksWithAndWithoutMaterialRelief() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                var camera = new BoardCamera();
                camera.resize(1280, 960);
                var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "dirt-materials");
                try {
                    assertTrue(output.isDirectory() || output.mkdirs());
                    terrain.setGrass(false);
                    for (var family : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.DIRT)) {
                        for (int levels : new int[] { 1, 2, 4 }) {
                            BoardScene scene = BoardScenes.scene(c -> BoardScenes.tile(c,
                                  family, c.getX() < 4 ? levels : 0, -1, 0));
                            Vector3 target = BoardGeometry.center(new Coords(3, 4), levels * .5f).add(40, 0, 0);
                            camera.camera.zoom = .14f;
                            camera.camera.near = 1;
                            camera.camera.far = 3000;
                            camera.camera.up.set(Vector3.Z);
                            camera.camera.position.set(target).add(650, -180, 300);
                            camera.camera.lookAt(target);
                            camera.camera.update();
                            frame.prepare(terrain, camera, scene);
                            terrain.update(scene);
                            terrain.animate(0, List.of());
                            for (String mode : List.of("color", "relief")) {
                                terrain.setNormalMaps(mode.equals("relief"));
                                for (int i = 0; i < 3; i++) { frame.render(terrain, camera, scene); }
                                GpuReviewFrame.save(new File(output, family + "-" + levels + "-" + mode + ".png"));
                                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), family + " " + levels + " " + mode);
                            }
                            if (family == BoardScene.Surface.GRASS && levels == 2) {
                                terrain.setGrass(true);
                                frame.render(terrain, camera, scene);
                                Thread.sleep(600); // Let newly visible roots finish their normal arrival fade.
                                frame.render(terrain, camera, scene);
                                GpuReviewFrame.save(new File(output, "GRASS-2-overgrown-oblique.png"));
                                camera.camera.position.set(target).add(0, 0, 800);
                                camera.camera.up.set(0, 1, 0);
                                camera.camera.lookAt(target);
                                camera.camera.update();
                                frame.render(terrain, camera, scene);
                                GpuReviewFrame.save(new File(output, "GRASS-2-overgrown-top.png"));
                                terrain.setGrass(false);
                                frame.render(terrain, camera, scene);
                                GpuReviewFrame.save(new File(output, "GRASS-2-material-top.png"));
                                checkTopViewTurf(scene, camera);
                            }
                        }
                    }
                } catch (Throwable error) { failure.set(error); }
                finally { terrain.dispose(); frame.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Native dirt materials", failure.get()); }
    }

    /** Turf must separate two earth faces even when grass instances are hidden. */
    private static void checkTopViewTurf(BoardScene scene, BoardCamera camera) {
        var tile = scene.tile(new Coords(3, 4));
        var walls = new BoardSurface(scene, tile).walls(scene, BoardGeometry.floor(scene));
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, 1280, 960);
        try {
            float middle = greenness(walls, camera, image, .9f, 1.1f);
            float rim = greenness(walls, camera, image, 1.85f, 1.98f);
            float earth = (greenness(walls, camera, image, .35f, .65f)
                  + greenness(walls, camera, image, 1.35f, 1.65f)) * .5f;
            assertTrue(middle > earth + .02f, "Overhead intermediate turf versus earth: " + middle + " / " + earth);
            assertTrue(rim > earth + .02f, "Overhead overgrown rim versus earth: " + rim + " / " + earth);
        } finally { image.dispose(); }
    }

    private static float greenness(List<BoardSurface.Face> faces, BoardCamera camera, Pixmap image, float low, float high) {
        float total = 0;
        int count = 0;
        for (var face : faces) {
            Vector3 point = face.a().cpy().add(face.b()).add(face.c()).scl(1f / 3);
            float height = point.z / BoardGeometry.level();
            if (height < low || height > high) { continue; }
            camera.camera.project(point);
            int x = Math.round(point.x), y = Math.round(point.y);
            if (x < 1 || x >= image.getWidth() - 1 || y < 1 || y >= image.getHeight() - 1) { continue; }
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int pixel = image.getPixel(x + dx, y + dy);
                float r = pixel >>> 24, g = (pixel >>> 16) & 255, b = (pixel >>> 8) & 255;
                total += (g - r) / Math.max(1, r + g + b);
                count++;
            }
        }
        assertTrue(count > 9, "Sample visible bank faces in elevation range " + low + ".." + high);
        return total / count;
    }
}
