/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Arrays;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.math.Matrix4;

/**
 * A model's finish (RigidGlb.Surface): roughness, metallic and subsurface scattering factors over an optional map
 * (R scattering, G roughness, B metallic; the model library owns it), the linear tint of its emission map with its
 * constant glow, and the parallax of its heightmap, which is the material's bump texture (TextureAttribute.Bump: its
 * own attribute type, so a program compiled with the parallax, modelReliefFlag, is never reused for a model without).
 */
final class GpuModelMaterial extends Attribute {
    static final long TYPE = register("boardModelSurface");
    private static final float[] WHITE = { 1, 1, 1 }, BLACK = new float[3];
    final float roughness;
    final float metallic;
    final float scatter;
    final Texture map;
    final float[] tint;
    final float[] glow;
    /** A board material's .tres gave it; otherwise the GLB's own glTF material did. */
    final boolean board;
    /** Depth along U and V in UV units; the deep parallax's minimum and maximum layers, 0 for one step. */
    final float[] relief;
    /** The albedo's alpha is a paint mask (RigidGlb.Data#paintMasks): the vertex colour, a colour slot's, tints the paint alone. */
    final boolean paintMask;
    /**
     * A building shell's surface, which the depth pre-pass draws (GpuTerrain.prepass): carried per draw through batching
     * and merges, since other objects' surfaces select the same programs.
     */
    final boolean occluder;
    /** Godot's premultiplied alpha (a .tres's blend_mode 4): only the diffuse share fades with the opacity (model.frag). */
    final boolean premultiplied;
    /** 0 a plain cutout; 1 a cutout's solid texels, whose faded ones blend later; 2 those faded ones (cutout-fade.glsl). */
    final float fade;
    /** The nearest depth in view and the fade weight lost per unit of depth beyond it, for one flush (GpuTerrain). */
    static final float[] fadeDepth = new float[2];
    /** The nearest glass's depth while glass splits a fade flush, and the side it draws: -1 behind it, 1 in front, 0 all (GpuCutoutFade). */
    static Texture fadeGlass;
    static float fadeSide;
    /**
     * Units a texture repeat along the board's axes (RigidGlb.Data#boardUVs), 0 for the authored UVs: the vertex stage
     * maps every texture from the board position (GpuUnitShader#linearVertex), so turned and repeated pieces join.
     */
    final float boardUV;

    GpuModelMaterial(float roughness, float metallic, Texture map) {
        this(roughness, metallic, 0, map, WHITE, BLACK, false, new float[4]);
    }

    GpuModelMaterial(float roughness, float metallic, float scatter, Texture map, float[] tint, float[] glow,
          boolean board, float[] relief) {
        this(roughness, metallic, scatter, map, tint, glow, board, relief, false);
    }

    GpuModelMaterial(float roughness, float metallic, float scatter, Texture map, float[] tint, float[] glow,
          boolean board, float[] relief, boolean paintMask) {
        this(roughness, metallic, scatter, map, tint, glow, board, relief, paintMask, false, false, 0, 0);
    }

    GpuModelMaterial(float roughness, float metallic, float scatter, Texture map, float[] tint, float[] glow,
          boolean board, float[] relief, boolean paintMask, float boardUV) {
        this(roughness, metallic, scatter, map, tint, glow, board, relief, paintMask, false, false, boardUV, 0);
    }

    private GpuModelMaterial(float roughness, float metallic, float scatter, Texture map, float[] tint, float[] glow,
          boolean board, float[] relief, boolean paintMask, boolean occluder, boolean premultiplied, float boardUV, float fade) {
        super(TYPE);
        this.roughness = roughness;
        this.metallic = metallic;
        this.scatter = scatter;
        this.map = map;
        this.tint = tint.clone();
        this.glow = glow.clone();
        this.board = board;
        this.relief = relief.clone();
        this.paintMask = paintMask;
        this.occluder = occluder;
        this.premultiplied = premultiplied;
        this.boardUV = boardUV;
        this.fade = fade;
    }

    /** This surface, a building shell's or not ({@link #occluder}). */
    GpuModelMaterial occluder(boolean shell) {
        return new GpuModelMaterial(roughness, metallic, scatter, map, tint, glow, board, relief, paintMask, shell,
              premultiplied, boardUV, fade);
    }

    /** This surface drawn with premultiplied alpha ({@link #premultiplied}). */
    GpuModelMaterial premultiplied() {
        return new GpuModelMaterial(roughness, metallic, scatter, map, tint, glow, board, relief, paintMask, occluder, true,
              boardUV, fade);
    }

    /** This surface as a cutout that follows its albedo's alpha, drawing its solid texels. */
    GpuModelMaterial fading() {
        return new GpuModelMaterial(roughness, metallic, scatter, map, tint, glow, board, relief, paintMask, occluder,
              premultiplied, boardUV, 1);
    }

    /** This cutout's faded texels (GpuCutoutFade#fadeMaterial). */
    GpuModelMaterial fadePass() {
        return new GpuModelMaterial(roughness, metallic, scatter, map, tint, glow, board, relief, paintMask, occluder,
              premultiplied, boardUV, 2);
    }

    /**
     * This surface on its mesh's own UVs ({@link #boardUV} 0): for pieces broken from it that move, whose vertices carry
     * the board UVs it was drawn with (MeepleShatter).
     */
    GpuModelMaterial ownUVs() {
        return new GpuModelMaterial(roughness, metallic, scatter, map, tint, glow, board, relief, paintMask, occluder,
              premultiplied, 0, fade);
    }

    static void register(DefaultShader shader) {
        // Only ruby programs have these uniforms (modelRubyFlag), and only ruby materials draw with them.
        shader.register("u_modelRuby", new BaseShader.LocalSetter() {
            @Override public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                target.set(id, 1f / (18 * BoardGeometry.hexScale()));
            }
        });
        shader.register("u_rubyVolume", new BaseShader.LocalSetter() {
            private final Matrix4 inverse = new Matrix4();
            @Override public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                inverse.set(renderable.worldTransform).inv();
                target.set(id, inverse);
            }
        });
        shader.register("u_rubyPlanes[0]", new BaseShader.LocalSetter() {
            @Override public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                float[] planes = GpuCrystalTransmission.planes(renderable.meshPart);
                target.program.setUniform4fv(target.loc(id), planes, 0, planes.length);
            }
        });
        shader.register("u_rubyFacets", new BaseShader.LocalSetter() {
            @Override public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                target.set(id, GpuCrystalTransmission.planes(renderable.meshPart).length / 4);
            }
        });
        shader.register("u_modelSurface", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var surface = attributes.get(GpuModelMaterial.class, TYPE);
                target.set(id, surface.roughness, surface.metallic, surface.scatter, surface.map == null ? 0f : 1f);
            }
        });
        shader.register("u_modelEmission", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var surface = attributes.get(GpuModelMaterial.class, TYPE);
                target.set(id, surface.tint[0], surface.tint[1], surface.tint[2]);
            }
        });
        shader.register("u_modelRelief", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                float[] relief = attributes.get(GpuModelMaterial.class, TYPE).relief;
                target.set(id, relief[0], relief[1], relief[2], relief[3]);
            }
        });
        shader.register("u_modelHeight", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var height = attributes.get(TextureAttribute.class, TextureAttribute.Bump);
                if (height != null) { target.set(id, height.textureDescription); }
            }
        });
        shader.register("u_modelFade", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                target.set(id, attributes.get(GpuModelMaterial.class, TYPE).fade);
            }
        });
        shader.register("u_modelFadeDepth", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                target.set(id, fadeDepth[0], fadeDepth[1]);
            }
        });
        shader.register("u_modelFadeSide", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                target.set(id, fadeSide);
            }
        });
        shader.register("u_modelFadeGlass", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                // Without glass it stays unread, but a sampler needs a unit its type may use: the colour map's.
                if (fadeGlass != null) { target.set(id, fadeGlass); }
                else { target.set(id, attributes.get(TextureAttribute.class, TextureAttribute.Diffuse).textureDescription); }
            }
        });
        shader.register("u_modelBoard", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                target.set(id, attributes.get(GpuModelMaterial.class, TYPE).board ? 1f : 0f);
            }
        });
        shader.register("u_modelPremultiplied", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                target.set(id, attributes.get(GpuModelMaterial.class, TYPE).premultiplied ? 1f : 0f);
            }
        });
        shader.register("u_modelBoardUV", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                target.set(id, attributes.get(GpuModelMaterial.class, TYPE).boardUV);
            }
        });
        shader.register("u_modelPaintMask", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                target.set(id, attributes.get(GpuModelMaterial.class, TYPE).paintMask ? 1f : 0f);
            }
        });
        shader.register("u_buildingPaint", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var paint = attributes.get(GpuBuildingPaint.class, GpuBuildingPaint.TYPE);
                if (paint == null) { target.set(id, 1f, 1f, 1f); }
                else { target.set(id, paint.colour.r, paint.colour.g, paint.colour.b); }
            }
        });
        shader.register("u_modelGlow", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var surface = attributes.get(GpuModelMaterial.class, TYPE);
                target.set(id, surface.glow[0], surface.glow[1], surface.glow[2]);
            }
        });
        shader.register("u_modelSurfaceMap", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var surface = attributes.get(GpuModelMaterial.class, TYPE);
                if (surface.map == null) { target.set(id, 0); }
                else { target.set(id, surface.map); }
            }
        });
        // A recolourable model's shared mesh drawn one by one (tilted or faded placements, hover copies, combat scars, the
        // side view): its placement's colours. Instanced batches carry them per instance instead (GpuTreeInstances).
        shader.register("u_slotColours[0]", new BaseShader.LocalSetter() {
            private final float[] linear = new float[12];

            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                float[] colours = RigidGlb.slotColours(renderable.userData);
                for (int slot = 0; slot < 4; slot++) {
                    int at = slot * 3;
                    if (colours[slot] < 0) {
                        linear[at] = linear[at + 1] = linear[at + 2] = -1;
                    } else {
                        int rgb = (int) colours[slot];
                        linear[at] = GpuColor.linear((rgb >> 16 & 255) / 255f);
                        linear[at + 1] = GpuColor.linear((rgb >> 8 & 255) / 255f);
                        linear[at + 2] = GpuColor.linear((rgb & 255) / 255f);
                    }
                }
                target.program.setUniform3fv(target.loc(id), linear, 0, linear.length);
            }
        });
    }

    @Override
    public Attribute copy() {
        return occluder(occluder);
    }

    @Override
    protected boolean equals(Attribute other) { return compareTo(other) == 0; }

    @Override
    public int hashCode() {
        int result = 31 * super.hashCode() + Float.floatToIntBits(roughness);
        result = 31 * result + Float.floatToIntBits(metallic);
        result = 31 * result + Float.floatToIntBits(scatter);
        result = 31 * result + Arrays.hashCode(tint);
        result = 31 * result + Arrays.hashCode(glow);
        result = 31 * result + Boolean.hashCode(board);
        result = 31 * result + Arrays.hashCode(relief);
        result = 31 * result + Boolean.hashCode(paintMask);
        result = 31 * result + Boolean.hashCode(occluder);
        result = 31 * result + Boolean.hashCode(premultiplied);
        result = 31 * result + Float.floatToIntBits(boardUV);
        result = 31 * result + Float.floatToIntBits(fade);
        return 31 * result + (map == null ? 0 : map.getTextureObjectHandle());
    }

    @Override
    public int compareTo(Attribute other) {
        if (type != other.type) { return Long.compare(type, other.type); }
        var surface = (GpuModelMaterial) other;
        int order = Float.compare(roughness, surface.roughness);
        if (order == 0) { order = Float.compare(metallic, surface.metallic); }
        if (order == 0) { order = Float.compare(scatter, surface.scatter); }
        if (order == 0) { order = Float.compare(fade, surface.fade); }
        if (order == 0) { order = Arrays.compare(tint, surface.tint); }
        if (order == 0) { order = Arrays.compare(glow, surface.glow); }
        if (order == 0) { order = Boolean.compare(board, surface.board); }
        if (order == 0) { order = Arrays.compare(relief, surface.relief); }
        if (order == 0) { order = Boolean.compare(paintMask, surface.paintMask); }
        if (order == 0) { order = Boolean.compare(occluder, surface.occluder); }
        if (order == 0) { order = Boolean.compare(premultiplied, surface.premultiplied); }
        if (order == 0) { order = Float.compare(boardUV, surface.boardUV); }
        if (order == 0) {
            order = Integer.compare(map == null ? 0 : map.getTextureObjectHandle(),
                  surface.map == null ? 0 : surface.map.getTextureObjectHandle());
        }
        return order;
    }
}
