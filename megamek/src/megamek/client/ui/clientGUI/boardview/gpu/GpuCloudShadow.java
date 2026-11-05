/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.math.Matrix4;

/**
 * Borrowed cloud transmission, shared by every lit surface through its environment's lighting
 * (GpuEnvironmentLighting.cloudShadow). GpuClouds owns the texture and updates the projection.
 */
final class GpuCloudShadow {
    private static final String MAIN = "void main() {";
    final Texture texture;
    final Matrix4 projection;
    boolean enabled = true;
    /** The layer can reflect sky light even when no directional source casts a cloud shadow. */
    boolean reflection;

    GpuCloudShadow(Texture texture, Matrix4 projection) {
        this.texture = texture;
        this.projection = projection;
    }

    static String prefix(Renderable renderable, DefaultShader.Config config) {
        var lighting = GpuEnvironmentLighting.of(renderable.environment);
        var diffuse = renderable.material.get(TextureAttribute.class, TextureAttribute.Diffuse);
        return DefaultShader.createPrefix(renderable, config)
              + (diffuse != null && GpuColorTexture.isColor(diffuse.textureDescription.texture) ? "#define linearTextureFlag\n" : "")
              + (renderable.material.has(GpuLinearColors.TYPE) ? "#define linearColorsFlag\n" : "")
              + (renderable.material.has(GpuModelMaterial.TYPE) ? "#define modelSurfaceFlag\n" : "")
              + (GpuCrystalTransmission.ruby(renderable.material) ? "#define modelRubyFlag\n" : "")
              + (renderable.material.has(GpuModelMaterial.TYPE) && renderable.material.has(TextureAttribute.Bump)
                    ? "#define modelReliefFlag\n" : "")
              + (RigidGlb.colourSlotted(renderable.meshPart.mesh.getVertexAttributes()) ? "#define colourSlotsFlag\n" : "")
              + (lighting != null ? "#define environmentLightingFlag\n" : "")
              + (lighting != null && lighting.crystals != null ? "#define crystalTransmissionFlag\n" : "")
              + (lighting != null && lighting.cloudShadow != null ? "#define cloudShadowFlag\n" : "")
              + (lighting != null && lighting.antialiasing ? "#define specularAntialiasingFlag\n" : "")
              + GpuAmbientOcclusion.prefix(renderable) + GpuSkyVisibility.prefix(renderable);
    }

    static void register(DefaultShader shader) {
        shader.register("u_cloudEnabled", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                GpuCloudShadow cloud = GpuEnvironmentLighting.cloudShadow(attributes);
                target.set(id, cloud != null && cloud.enabled ? 1f : 0f);
            }
        });
        shader.register("u_cloudReflection", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                GpuCloudShadow cloud = GpuEnvironmentLighting.cloudShadow(attributes);
                target.set(id, cloud != null && cloud.reflection ? 1f : 0f);
            }
        });
        shader.register("u_cloudShadow", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                GpuCloudShadow cloud = GpuEnvironmentLighting.cloudShadow(attributes);
                if (cloud != null) { target.set(id, cloud.texture); }
            }
        });
        shader.register("u_cloudProjection", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                GpuCloudShadow cloud = GpuEnvironmentLighting.cloudShadow(attributes);
                if (cloud != null) { target.set(id, cloud.projection); }
            }
        });
    }

    static String vertex(String source) {
        source = insert(source, MAIN, "out vec3 v_cloudPosition;\n" + MAIN);
        String position = "gl_Position = u_projViewTrans * pos;";
        return insert(source, position, position + "\nv_cloudPosition = pos.xyz;\n");
    }

    /** A lit surface's fragment stage: its one lighting function attenuates direct light under clouds. */
    static String fragment(String source) {
        String declarations = GpuShaderSource.read("cloud-shadow.glsl");
        // Shared lighting functions can sample clouds before main; declare their inputs before those helpers.
        String anchor = source.contains("// terrain-lighting-functions") ? "// terrain-lighting-functions"
              : source.contains("// model-lighting-functions") ? "// model-lighting-functions" : MAIN;
        source = insert(source, anchor, declarations + "\n" + anchor);
        return insert(source, "albedo *= ambient + direct;",
              GpuShaderSource.read("cloud-surface.glsl") + "\nalbedo *= ambient + direct;");
    }

    private static String insert(String source, String anchor, String replacement) {
        return GpuShaderSource.replaceOnce(source, anchor, replacement, "cloud");
    }
}
