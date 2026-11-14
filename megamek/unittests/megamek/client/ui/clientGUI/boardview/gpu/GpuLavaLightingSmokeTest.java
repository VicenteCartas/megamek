/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.client.ui.clientGUI.boardview.BoardFieldOfView;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Actual unit/prop shader response, visibility gating and state removal; also captures the bank in the compositor. */
@Tag("on-demand")
class GpuLavaLightingSmokeTest {
    @Test
    void moltenTerrainIlluminatesObjectsAndHiddenOrRemovedEmittersDoNot() {
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1200, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var assets = new GpuAssets();
                var batch = new ModelBatch(GpuUnitShader.provider());
                var model = new ModelBuilder().createBox(14, 14, 24,
                      new Material(ColorAttribute.createDiffuse(.65f, .65f, .65f, 1)),
                      VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(0, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try (var surface = new GpuSceneTestOutput(1200, 900)) {
                    var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "lava-light");
                    assertTrue(output.isDirectory() || output.mkdirs());
                    BoardScene scene = bank(BoardLiquid.Kind.MAGMA, false);
                    var camera = new BoardCamera();
                    camera.resize(1200, 900);
                    camera.setIsometric(true);
                    camera.camera.zoom = .17f;
                    var position = BoardGeometry.center(new Coords(2, 2), 0).add(18, 0, 12);
                    camera.center(position);
                    var object = new ModelInstance(model);
                    object.transform.setToTranslation(position);
                    var lights = new GpuLavaLighting(assets);
                    // Local lights reach a draw through its environment's lighting; this one's sky is black.
                    var lighting = new GpuEnvironmentLighting();
                    lighting.lavaLighting = lights;
                    var environment = new Environment();
                    environment.set(ColorAttribute.createAmbientLight(.015f, .015f, .015f, 1));
                    environment.set(lighting);
                    int[] unlit = draw(surface, batch, object, environment, camera);
                    GpuReviewFrame.save(new File(output, "object-no-spill.png"));
                    lights.update(scene);
                    int[] lit = draw(surface, batch, object, environment, camera);
                    GpuReviewFrame.save(new File(output, "object-lava-spill.png"));
                    assertTrue(red(lit) > red(unlit) + .5, "Molten light must reach the standard object shader");
                    lights.update(bank(BoardLiquid.Kind.MAGMA_CRUST, false));
                    assertTrue(red(draw(surface, batch, object, environment, camera)) < red(lit) - .4,
                          "Cooling leaves much weaker residual heat");
                    lights.update(bank(BoardLiquid.Kind.MAGMA, true));
                    assertArrayEquals(unlit, draw(surface, batch, object, environment, camera),
                          "Blocked sources cannot reveal heat");
                    lights.update(bank(BoardLiquid.Kind.NONE, false));
                    assertArrayEquals(unlit, draw(surface, batch, object, environment, camera),
                          "Changing board clears old emitters");
                    lights.update(scene);
                    assertArrayEquals(lit, draw(surface, batch, object, environment, camera),
                          "The same source restores the same light");
                    lighting.lavaLighting = null;
                    assertArrayEquals(unlit, draw(surface, batch, object, environment, camera),
                          "An environment without lava resets uniforms");
                    lighting.lavaLighting = lights;
                    var at = new Coords(3, 2);
                    // A mushroom near the receiving object's visible side, rather than at the edge of its falloff.
                    Vector3 root = position.cpy().add(20, -18, -12);
                    var fungus = GpuFungus.light(5, at, BoardFungus.CUPS.getFirst(),
                          new BoundingBox(root.cpy().add(-16, -16, 0), root.cpy().add(16, 16, 30)));
                    lights.setFungus(List.of(fungus));
                    lights.update(bank(BoardLiquid.Kind.NONE, false));
                    double fungalRed = red(draw(surface, batch, object, environment, camera));
                    assertTrue(fungalRed > red(unlit) + .15,
                          "Mushrooms illuminate nearby objects: red increase " + (fungalRed - red(unlit)));
                    lights.update(bank(BoardLiquid.Kind.NONE, true));
                    assertArrayEquals(unlit, draw(surface, batch, object, environment, camera),
                          "Hidden fungi do not leak light");
                    lights.update(bank(BoardLiquid.Kind.NONE, false), false);
                    assertArrayEquals(unlit, draw(surface, batch, object, environment, camera),
                          "Tactical View excludes prop lights");
                    lights.setFungus(List.of());
                    lights.update(bank(BoardLiquid.Kind.NONE, false));
                    assertArrayEquals(unlit, draw(surface, batch, object, environment, camera),
                          "Retiring props retires their lights");
                    lighting.dispose();
                    surface.pixels().end();
                    terrain.update(scene);
                    terrain.animate(.5f, List.of());
                    frame.render(terrain, camera, scene, List.of(object), batch);
                    GpuReviewFrame.save(new File(output, "lava-bank-night.png"));
                    frame.configure(new BoardAtmosphere.Settings(9, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                    frame.render(terrain, camera, scene, List.of(object), batch);
                    GpuReviewFrame.save(new File(output, "lava-bank-day.png"));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); assets.dispose(); terrain.dispose(); batch.dispose(); model.dispose(); Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) throw new AssertionError("Lava illumination", failure.get());
    }

    private static int[] draw(GpuSceneTestOutput surface, ModelBatch batch, ModelInstance object,
          Environment environment, BoardCamera camera) {
        surface.begin(0, 0, 0, 1);
        batch.begin(camera.camera);
        batch.render(object, environment);
        batch.end();
        surface.display();
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, 1200, 900);
        try {
            int[] pixels = new int[1200 * 900];
            for (int y = 0; y < 900; y++) for (int x = 0; x < 1200; x++) pixels[y * 1200 + x] = image.getPixel(x, y);
            return pixels;
        } finally { image.dispose(); }
    }

    private static double red(int[] pixels) {
        long total = 0;
        for (int color : pixels) total += color >>> 24;
        return total / (double) pixels.length;
    }

    private static BoardScene bank(BoardLiquid.Kind kind, boolean hidden) {
        var pixels = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));
        var tiles = new ArrayList<BoardScene.Tile>();
        var fov = new ArrayList<BoardFieldOfView.Hex>();
        for (int x = 0; x < 5; x++) for (int y = 0; y < 5; y++) {
            var liquid = x >= 3 && kind != BoardLiquid.Kind.NONE ? new BoardLiquid(kind, "", 0) : BoardLiquid.NONE;
            tiles.add(BoardScenes.newTile(new Coords(x, y), 0, -1, false, 0, BoardScene.Surface.ROCK,
                  List.of(), List.of(), liquid, null));
            fov.add(hidden && x >= 3 ? new BoardFieldOfView.Hex(BoardFieldOfView.Visibility.BLOCKED, 0)
                  : BoardFieldOfView.Hex.VISIBLE);
        }
        BoardScene scene = new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
        return new BoardScene(scene.boardId(), scene.width(), scene.height(), scene.tiles(), scene.units(),
              scene.plannedPath(), scene.selectedId(), scene.phase(), scene.commands(), scene.light(), scene.firingLines(),
              scene.rangeBorders(), scene.markers(), scene.tactical(), scene.rangeLabels(),
              new BoardFieldOfView(5, 5, fov, 0, 0, hidden, false, false));
    }
}
