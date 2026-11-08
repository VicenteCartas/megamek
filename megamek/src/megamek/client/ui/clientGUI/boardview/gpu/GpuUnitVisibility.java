/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.shaders.DepthShader;
import com.badlogic.gdx.graphics.g3d.utils.DepthShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.ShaderProvider;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.HdpiUtils;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.ScreenUtils;

/** Occluded parts of visible units and opted-in markers. Owns GL resources; never changes scene materials or depth. */
final class GpuUnitVisibility implements Disposable {
    static final float DEFAULT_OUTLINE_INTENSITY = 0.55f;
    /** Screen-pixel sample step, also used when picking the coloured edge outside a unit's mesh. */
    static final float OUTLINE_STEP = 1.5f;
    private ShaderProgram shader;
    private final Mesh quad;
    private final ModelBatch colorBatch;
    private final Rectangle screenBounds = new Rectangle();
    private final Vector3 corner = new Vector3();
    private Texture unitDepth;
    private FrameBuffer unitColors;
    /** Outline-only building depth; the scene keeps its cutaways for fog, effects and floor labels. */
    private Texture buildingDepth;
    private FrameBuffer buildings;
    private boolean depthCurrent;

    GpuUnitVisibility() {
        shader = GpuShaderManager.program(() -> GpuAtmosphere.shader("unit-visibility.frag"), next -> shader = next);
        quad = GpuAtmosphere.screenQuad();
        colorBatch = new ModelBatch(GpuShaderManager.provider("Unit outlines", GpuUnitVisibility::colorProvider),
              new GpuOpaqueSorter());
    }

    private static ShaderProvider colorProvider() {
        DepthShader.Config config = new DepthShader.Config();
        config.defaultCullFace = GL20.GL_BACK;
        config.depthBufferOnly = true;
        config.vertexShader = GpuShaderSource.read("depth.vert");
        config.fragmentShader = GpuShaderSource.read("unit-color.frag");
        return new DepthShaderProvider(config) {
            @Override
            protected Shader createShader(Renderable renderable) {
                return new DepthShader(renderable, this.config, GpuGlsl.compile("GPU unit outline",
                      DepthShader.createPrefix(renderable, this.config),
                      this.config.vertexShader, this.config.fragmentShader)) {
                    private final int outlineColor = register("u_outlineColor");

                    /** This program captures depth, also of what writes none in the scene (MeepleVisual.ICON_DEPTH). */
                    @Override
                    protected void bindMaterial(Attributes attributes) {
                        super.bindMaterial(attributes);
                        context.setDepthMask(true);
                    }

                    @Override
                    public void render(Renderable part, Attributes attributes) {
                        set(outlineColor, part.userData instanceof Color color ? color : Color.LIGHT_GRAY);
                        super.render(part, attributes);
                    }
                };
            }
        };
    }

    /** Adds the units' depth to the current target without colour: what draws after it lies under the Tactical View's icons. */
    void renderDepth(Camera camera, List<ModelInstance> units) {
        Gdx.gl.glColorMask(false, false, false, false);
        drawUnits(camera, units);
        Gdx.gl.glColorMask(true, true, true, true);
    }

    private void drawUnits(Camera camera, List<ModelInstance> units) {
        colorBatch.begin(camera);
        units.forEach(unit -> GpuUnitInstance.renderDepth(colorBatch, unit));
        colorBatch.end();
    }

    void render(Camera camera, List<ModelInstance> units, Texture sceneDepth, int bottom, float intensity, float scale) {
        render(camera, units, sceneDepth, bottom, intensity, scale, null);
    }

    void render(Camera camera, List<ModelInstance> units, Texture sceneDepth, int bottom, float intensity, float scale,
          UnitBounds.Frame bounds) {
        render(camera, units, sceneDepth, bottom, intensity, scale, bounds, null);
    }

    void render(Camera camera, List<ModelInstance> units, Texture sceneDepth, int bottom, float intensity, float scale,
          UnitBounds.Frame bounds, Texture effectOpacity) {
        render(camera, units, sceneDepth, bottom, intensity, scale, bounds, effectOpacity, null);
    }

    void render(Camera camera, List<ModelInstance> units, Texture sceneDepth, int bottom, float intensity, float scale,
          UnitBounds.Frame bounds, Texture effectOpacity, GpuTerrain terrain) {
        depthCurrent = false;
        if (intensity <= 0 || units.isEmpty()) {
            return;
        }
        screenBounds(camera, units, scale, bounds);
        if (screenBounds.width <= 0 || screenBounds.height <= 0) { return; }
        int width = sceneDepth.getWidth(), height = sceneDepth.getHeight();
        if (unitDepth == null || unitDepth.getWidth() != width || unitDepth.getHeight() != height) {
            disposeBuffers();
            unitColors = GpuAtmosphere.buffer(width, height, false);
            try {
                unitDepth = GpuAtmosphere.attachDepthTexture(unitColors);
            } catch (RuntimeException failure) {
                disposeBuffers();
                throw failure;
            }
        }
        // One capture keeps depth and team color on the same nearest surface, with the depth shader's alpha cutouts.
        unitColors.begin();
        Gdx.gl.glDepthMask(true);
        ScreenUtils.clear(0, 0, 0, 0, true);
        drawUnits(camera, units);
        unitColors.end();
        depthCurrent = true;

        boolean buildingCover = terrain != null && terrain.hasFadedBuildings();
        if (buildingCover) {
            if (buildings == null) {
                buildings = GpuAtmosphere.buffer(width, height, false);
                try {
                    buildingDepth = GpuAtmosphere.attachDepthTexture(buildings);
                } catch (RuntimeException failure) {
                    disposeBuffers();
                    throw failure;
                }
            }
            buildings.begin();
            try {
                Gdx.gl.glDepthMask(true);
                ScreenUtils.clear(0, 0, 0, 0, true);
                terrain.renderOutlineDepth(camera);
            } finally { buildings.end(); }
        }

        HdpiUtils.glViewport(0, bottom, (int) camera.viewportWidth, (int) camera.viewportHeight);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        HdpiUtils.glScissor((int) screenBounds.x, bottom + (int) screenBounds.y,
              (int) screenBounds.width, (int) screenBounds.height);
        try {
            shader.bind();
            sceneDepth.bind(0);
            unitDepth.bind(1);
            unitColors.getColorBufferTexture().bind(2);
            (effectOpacity == null ? sceneDepth : effectOpacity).bind(3);
            shader.setUniformi("u_sceneDepth", 0);
            shader.setUniformi("u_unitDepth", 1);
            shader.setUniformi("u_unitColors", 2);
            if (buildingCover) { buildingDepth.bind(4); }
            shader.setUniformi("u_buildingDepth", buildingCover ? 4 : 0);
            shader.setUniformi("u_effectOpacity", 3);
            shader.setUniformf("u_effectSize", effectOpacity == null ? 0 : effectOpacity.getWidth(),
                  effectOpacity == null ? 0 : effectOpacity.getHeight());
            // A small world-space tolerance avoids highlighting an exposed surface due to depth rounding.
            shader.setUniformf("u_bias", 0.05f * BoardGeometry.hexScale());
            shader.setUniformf("u_projectionDepth", camera.projection.val[Matrix4.M22], camera.projection.val[Matrix4.M23],
                  camera.projection.val[Matrix4.M32], camera.projection.val[Matrix4.M33]);
            // World positions and hexes of both the unit and what stands before it, for the own-hex exemption.
            shader.setUniformMatrix("u_inverseView", camera.invProjectionView);
            shader.setUniformf("u_groundBoard", 0, 0, BoardGeometry.width(), BoardGeometry.height());
            shader.setUniformf("u_levelHeight", BoardGeometry.level());
            shader.setUniformf("u_step", OUTLINE_STEP * scale / camera.viewportWidth, OUTLINE_STEP * scale / camera.viewportHeight);
            shader.setUniformf("u_intensity", MathUtils.clamp(intensity, 0, 1));
            quad.render(shader, GL20.GL_TRIANGLES);
        } finally {
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
            Gdx.gl.glDisable(GL20.GL_BLEND);
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        }
    }

    /**
     * The see-through colour's alpha for a unit standing in tile: the height, in quarter levels offset by 128, below
     * which that hex's own relief, grass and scatter stay. The pass never counts them as hiding the unit.
     */
    static float ownHex(BoardScene.Tile tile) {
        float top = tile.elevation() + BoardRelief.decoration(tile) / BoardGeometry.level();
        return MathUtils.clamp((float) Math.ceil(top * 4) + 128, 1, 255) / 255;
    }

    /** Borrow this frame's existing unit capture; disabled/empty outlines must never expose stale camera depth. */
    Texture depthTexture() {
        return depthCurrent ? unitDepth : null;
    }

    /** Project a conservative union, including the two-sample halo and rounding on HiDPI displays. */
    private void screenBounds(Camera camera, List<ModelInstance> units, float scale, UnitBounds.Frame bounds) {
        screenBounds.set(0, 0, camera.viewportWidth, camera.viewportHeight);
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (ModelInstance unit : units) {
            var box = bounds == null ? UnitBounds.world(unit) : bounds.get(unit);
            for (int i = 0; i < 8; i++) {
                corner.set((i & 1) == 0 ? box.min.x : box.max.x, (i & 2) == 0 ? box.min.y : box.max.y,
                      (i & 4) == 0 ? box.min.z : box.max.z);
                // A perspective projection mirrors points behind the eye: keep the whole viewport for such a unit.
                if ((corner.x - camera.position.x) * camera.direction.x
                      + (corner.y - camera.position.y) * camera.direction.y
                      + (corner.z - camera.position.z) * camera.direction.z <= camera.near) { return; }
                corner.prj(camera.combined);
                float x = (corner.x + 1) * camera.viewportWidth / 2;
                float y = (corner.y + 1) * camera.viewportHeight / 2;
                minX = Math.min(minX, x); minY = Math.min(minY, y);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
            }
        }
        float margin = (float) Math.ceil(2 * OUTLINE_STEP * scale) + 2;
        minX = Math.max(0, (float) Math.floor(minX - margin));
        minY = Math.max(0, (float) Math.floor(minY - margin));
        maxX = Math.min(camera.viewportWidth, (float) Math.ceil(maxX + margin));
        maxY = Math.min(camera.viewportHeight, (float) Math.ceil(maxY + margin));
        screenBounds.set(minX, minY, maxX - minX, maxY - minY);
    }

    private void disposeBuffers() {
        depthCurrent = false;
        if (unitDepth != null) { unitDepth.dispose(); unitDepth = null; }
        if (unitColors != null) { unitColors.dispose(); unitColors = null; }
        if (buildingDepth != null) { buildingDepth.dispose(); buildingDepth = null; }
        if (buildings != null) { buildings.dispose(); buildings = null; }
    }

    @Override
    public void dispose() {
        disposeBuffers();
        colorBatch.dispose();
        quad.dispose();
        GpuShaderManager.dispose(shader);
    }
}
