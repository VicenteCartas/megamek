/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;

/** Complementary height clips share the original shell mesh and leave picking and shadows intact. */
final class GpuBuildingCutaway extends Attribute {
    static final long TYPE = register("boardBuildingCutaway");
    final float floor, ceiling;
    final boolean inside;

    GpuBuildingCutaway(float floor, float ceiling, boolean inside) {
        super(TYPE);
        this.floor = floor;
        this.ceiling = ceiling;
        this.inside = inside;
    }

    /** Shade before the color clip so mapped normals and optical coats retain their neighboring derivatives. */
    static String fragment(String source, String height) {
        return GpuShaderSource.replaceOnce(source, "void main() {", "void cutawayBaseSurface() {", "cutaway fragment")
              + "\nuniform vec3 u_buildingCutaway;\nvoid main() {\ncutawayBaseSurface();\n" + clip(height) + "}\n";
    }

    /** The depth-only pass has no material frame to preserve and retains its original early clip. */
    static String depthFragment(String source, String height) {
        return GpuShaderSource.replaceOnce(source, "void main() {", "uniform vec3 u_buildingCutaway;\nvoid main() {\n"
              + clip(height), "cutaway depth fragment");
    }

    private static String clip(String height) {
        // Keep horizontal caps on the storey boundaries opaque. A small margin gives colour and depth
        // interpolation the same answer for the newly solid wall ends and slabs at those exact heights.
        return "bool insideStorey = " + height + " > u_buildingCutaway.x + 0.001 && " + height + " < u_buildingCutaway.y - 0.001;\n"
              + "if (insideStorey != (u_buildingCutaway.z > 0.0)) discard;\n";
    }

    static String depthVertex(String source, boolean instanced) {
        return source.replace("void main() {", "out float v_buildingHeight;\nuniform mat4 u_buildingWorld;\nvoid main() {\n"
              + "v_buildingHeight = " + (instanced ? "instancePosition(a_position).z"
                    : "(u_buildingWorld * vec4(a_position, 1.0)).z") + ";\n");
    }

    static void register(DefaultShader shader) {
        shader.register("u_buildingCutaway", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var clip = attributes.get(GpuBuildingCutaway.class, TYPE);
                if (clip != null) { target.set(id, clip.floor, clip.ceiling, clip.inside ? 1f : 0f); }
            }
        });
        shader.register("u_buildingWorld", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                target.set(id, renderable.worldTransform);
            }
        });
    }

    @Override
    public Attribute copy() { return new GpuBuildingCutaway(floor, ceiling, inside); }

    @Override
    protected boolean equals(Attribute other) { return compareTo(other) == 0; }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * super.hashCode() + Float.floatToIntBits(floor))
              + Float.floatToIntBits(ceiling)) + Boolean.hashCode(inside);
    }

    @Override
    public int compareTo(Attribute other) {
        if (type != other.type) { return Long.compare(type, other.type); }
        var clip = (GpuBuildingCutaway) other;
        int result = Float.compare(floor, clip.floor);
        if (result == 0) { result = Float.compare(ceiling, clip.ceiling); }
        return result == 0 ? Boolean.compare(inside, clip.inside) : result;
    }
}
