/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real assembled base/floor/roof modules, shared glass and mixed-atlas windows at both LODs. */
@Tag("on-demand")
class GpuModularNightSmokeTest {
    @Test void assembledWindowsLightOnlyAtNightAcrossLods() throws Exception {
        File output = new File("build/gpu-board-review/modular-night"); Files.createDirectories(output.toPath());
        var scene = BoardScenes.scene(at -> BoardScenes.tile(at, BoardScene.Surface.CONCRETE, 0, -1, 0));
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1000, 900); config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var terrain = new GpuTerrain(); var assets = new GpuAssets(); var batch = new ModelBatch(GpuUnitShader.provider());
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(10, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    terrain.update(scene);
                    var camera = new BoardCamera(); camera.resize(1000, 900); camera.setIsometric(true);
                    var centre = BoardGeometry.center(new Coords(4, 4), 0);
                    camera.center(centre.cpy().add(0, 0, 29)); camera.camera.zoom = .15f; camera.update();
                    for (String family : List.of("standard/building-light", "standard/fortress-light", "lunar_habitat/lunar_habitat")) {
                        var building = assets.building("buildings/" + family, 3, new int[6], 17, false);
                        assertNotNull(building, family);
                        for (int lod = 0; lod < 2; lod++) {
                            var object = building.instance(lod);
                            object.transform.setToTranslation(centre).scale(BoardGeometry.hexScale(), BoardGeometry.hexScale(), BoardGeometry.hexScale());
                            int lights = 0;
                            for (var material : object.materials) {
                                if (material.has(GpuModelLight.TYPE)) { lights++; }
                            }
                            assertTrue(lights > 0, family + " LOD" + lod + " retains window night controls");
                            String name = family.substring(0, family.indexOf('/')) + "-lod" + lod;
                            GpuBuilding.tint(object, megamek.common.board.BoardDecoration.Colours.of("#638ca6"));
                            for (int hour : new int[] {10, 23}) {
                                frame.configure(new BoardAtmosphere.Settings(hour, 0, 0,
                                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                                frame.render(terrain, camera, scene, List.of(object), batch);
                                GpuReviewFrame.save(new File(output, name + (hour == 10 ? "-day.png" : "-night.png")));
                            }
                            Pixmap lit = Pixmap.createFromFrameBuffer(0, 0, 1000, 900);
                            try {
                                for (var material : object.materials) {
                                    if (material.has(GpuModelLight.TYPE)) { material.set(new GpuModelLight(0, 0, 1, true)); }
                                }
                                frame.render(terrain, camera, scene, List.of(object), batch);
                                GpuReviewFrame.save(new File(output, name + "-night-off.png"));
                                Pixmap off = Pixmap.createFromFrameBuffer(0, 0, 1000, 900);
                                try {
                                    int changed = 0;
                                    for (int y = 0; y < 900; y++) {
                                        for (int x = 0; x < 1000; x++) {
                                            int a = lit.getPixel(x, y), b = off.getPixel(x, y);
                                            int bright = (a >>> 24) + ((a >>> 16) & 255) + ((a >>> 8) & 255);
                                            int dark = (b >>> 24) + ((b >>> 16) & 255) + ((b >>> 8) & 255);
                                            if (bright > dark + 13) { changed++; }
                                        }
                                    }
                                    assertTrue(changed > 40, name + " must illuminate its window texels: " + changed);
                                } finally { off.dispose(); }
                            } finally { lit.dispose(); }
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { batch.dispose(); frame.dispose(); terrain.dispose(); assets.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Modular day/night windows", failure.get()); }
    }
}
