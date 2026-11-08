/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;

/** Opaque solid/flowing magma materials; GpuAssets owns their repeating maps. */
final class GpuMagmaShader extends Attribute {
    static final long TYPE = register("boardMagmaSurface");
    static final int CRUST = 0, LAVA = 1, FALL = 2, BANK = 3;
    private static final float[] CRUST_LAYERS = GpuAssets.magmaLayers(false), LAVA_LAYERS = GpuAssets.magmaLayers(true);

    private final int mode;
    private final float emissionScale;
    /** GpuAssets.coverage of the set's maps: a wider texture repeats less often at the same texel density. */
    private final float coverage;
    /** The lava's field: its currents and bank distance for the melt, and how close a cooled bank is to it. */
    private final GpuWaterShader.Field field;

    private GpuMagmaShader(int mode, GpuWaterShader.Field field, float emissionScale, float coverage) {
        super(TYPE);
        this.mode = mode;
        this.emissionScale = emissionScale;
        this.coverage = coverage;
        this.field = mode == CRUST ? null : field;
    }

    GpuMagmaShader withField(GpuWaterShader.Field field) {
        return new GpuMagmaShader(mode, field, emissionScale, coverage);
    }

    boolean flowing() { return mode == LAVA || mode == FALL; }

    @Override
    public GpuMagmaShader copy() { return withField(field); }

    @Override
    public int hashCode() {
        return 31 * (31 * super.hashCode() + mode) + System.identityHashCode(field);
    }

    @Override
    public int compareTo(Attribute other) {
        if (type != other.type) { return Long.compare(type, other.type); }
        GpuMagmaShader magma = (GpuMagmaShader) other;
        int order = Integer.compare(mode, magma.mode);
        return order != 0 ? order : Integer.compare(System.identityHashCode(field), System.identityHashCode(magma.field));
    }

    static Material material(GpuAssets assets, int mode, GpuWaterShader.Field field) {
        boolean molten = mode == LAVA || mode == FALL;
        float emissionScale = molten ? assets.lavaEmission().scale() : 0;
        return new Material("magma:" + mode, new GpuMagmaShader(mode, field, emissionScale,
              assets.coverage(GpuAssets.magmaName(molten))), IntAttribute.createCullFace(GL20.GL_NONE));
    }

    static void register(DefaultShader shader, Texture fallback) {
        shader.register("u_magmaLayers", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var magma = attributes.get(GpuMagmaShader.class, TYPE);
                if (magma != null) {
                    float[] layers = magma.flowing() ? LAVA_LAYERS : CRUST_LAYERS;
                    target.set(id, layers[0], layers[1], layers[2], layers[3]);
                }
            }
        });
        shader.register("u_magmaEmissionScale", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var magma = attributes.get(GpuMagmaShader.class, TYPE);
                if (magma != null) { target.set(id, magma.emissionScale); }
            }
        });
        shader.register("u_magmaCoverage", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var magma = attributes.get(GpuMagmaShader.class, TYPE);
                target.set(id, magma != null ? magma.coverage : 1f);
            }
        });
        shader.register("u_magmaMode", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var magma = attributes.get(GpuMagmaShader.class, TYPE);
                if (magma != null) { target.set(id, (float) magma.mode); }
            }
        });
        shader.register("u_magmaField", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var magma = attributes.get(GpuMagmaShader.class, TYPE);
                // Crust disables the field through u_magmaFieldMap, but its sampler stays active in the program.
                // Always bind a 2D texture: a stale unit can be reused for an array and invalidate the entire draw.
                target.set(id, magma != null && magma.field != null ? magma.field.texture : fallback);
            }
        });
        shader.register("u_magmaFieldMap", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var magma = attributes.get(GpuMagmaShader.class, TYPE);
                if (magma != null && magma.field != null) {
                    var field = magma.field;
                    target.set(id, field.scaleX, field.scaleY, field.offsetX, field.offsetY);
                } else { target.set(id, 0f, 0f, 0f, 0f); }
            }
        });
    }
}
