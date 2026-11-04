/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;

/** Ruby absorption along the sun's rays, on the nearest opaque receiver in the existing shadow map. */
final class GpuCrystalTransmission implements Disposable {
    /** The authored ruby material carries optical transmission independently of alpha blending. */
    static boolean ruby(Material material) { return "crystal".equals(material.id); }

    private FrameBuffer target;
    private ShaderProgram shader;
    private Texture white;
    private final Matrix4 projection = new Matrix4();
    private long captured = Long.MIN_VALUE;
    private boolean enabled;

    void update(GpuShadowLight light, long revision, List<Renderable> parts) {
        enabled = light != null && !parts.isEmpty();
        if (!enabled) { captured = Long.MIN_VALUE; return; }
        var depth = light.getDepthMap().texture;
        long key = revision;
        for (Renderable part : parts) {
            key = key * 31 + System.identityHashCode(part.meshPart.mesh);
            key = key * 31 + part.meshPart.offset;
            key = key * 31 + part.worldTransform.hashCode();
        }
        if (target == null || target.getWidth() != depth.getWidth()) {
            if (target != null) { target.dispose(); }
            target = new FrameBuffer(Pixmap.Format.RGB888, depth.getWidth(), depth.getHeight(), false);
            target.getColorBufferTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            captured = Long.MIN_VALUE;
        }
        if (captured == key) { return; }
        projection.set(light.getProjViewTrans());
        if (shader == null) {
            shader = new ShaderProgram("""
                  in vec3 a_position;
                  in vec2 a_texCoord0;
                  uniform mat4 u_projection, u_world;
                  out vec2 uv;
                  void main() { uv=a_texCoord0; gl_Position=u_projection*u_world*vec4(a_position,1.0); }
                  """, """
                  uniform sampler2D u_pigment;
                  in vec2 uv;
                  void main() {
                      // The authored ruby pigment defines wavelength absorption; overlapping bodies multiply it.
                      vec3 pigment=texture(u_pigment,uv).rgb;
                      fragColor=vec4(pow(clamp(pigment,vec3(.001),vec3(.86)),vec3(.5)),1.0);
                  }
                  """);
            if (!shader.isCompiled()) { throw new IllegalStateException(shader.getLog()); }
        }
        target.begin();
        // Borrow, never own or clear, the finished opaque depth including terrain, buildings and moving units.
        Gdx.gl.glFramebufferTexture2D(GL20.GL_FRAMEBUFFER, GL20.GL_DEPTH_ATTACHMENT, GL20.GL_TEXTURE_2D,
              depth.getTextureObjectHandle(), 0);
        try {
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
            Gdx.gl.glClearColor(1, 1, 1, 1);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
            Gdx.gl.glDepthMask(false);
            Gdx.gl.glEnable(GL20.GL_CULL_FACE);
            Gdx.gl.glCullFace(GL20.GL_BACK);
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_DST_COLOR, GL20.GL_ZERO);
            GpuShadowLight.depthClamp(true);
            shader.bind();
            shader.setUniformMatrix("u_projection", projection);
            shader.setUniformi("u_pigment", 0);
            for (Renderable part : parts) {
                var pigment = part.material.get(TextureAttribute.class, TextureAttribute.Diffuse);
                if (pigment == null) { continue; }
                pigment.textureDescription.texture.bind(0);
                Gdx.gl.glFrontFace(part.worldTransform.det3x3() < 0 ? GL20.GL_CW : GL20.GL_CCW);
                shader.setUniformMatrix("u_world", part.worldTransform);
                part.meshPart.render(shader);
            }
            captured = key;
        } finally {
            GpuShadowLight.depthClamp(false);
            Gdx.gl.glFrontFace(GL20.GL_CCW);
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glDisable(GL20.GL_BLEND);
            Gdx.gl.glFramebufferTexture2D(GL20.GL_FRAMEBUFFER, GL20.GL_DEPTH_ATTACHMENT, GL20.GL_TEXTURE_2D, 0, 0);
            target.end();
        }
    }

    private Texture texture() {
        if (target != null) { return target.getColorBufferTexture(); }
        if (white == null) {
            var pixel = new Pixmap(1, 1, Pixmap.Format.RGB888);
            pixel.setColor(1, 1, 1, 1); pixel.fill();
            white = new Texture(pixel); pixel.dispose();
        }
        return white;
    }

    private static GpuCrystalTransmission of(Attributes attributes) {
        var lighting = GpuEnvironmentLighting.of(attributes);
        return lighting == null ? null : lighting.crystals;
    }

    static void register(DefaultShader shader) {
        shader.register("u_crystalEnabled", new BaseShader.LocalSetter() {
            @Override public void set(BaseShader target, int id, Renderable part, Attributes attributes) {
                var source = of(attributes);
                var blend = part.material.get(BlendingAttribute.class, BlendingAttribute.Type);
                boolean opaque = !ruby(part.material)
                      && (blend == null || !blend.blended || part.material.has(FloatAttribute.AlphaTest));
                // Ruby bodies use their own scattering; the accumulated map belongs to opaque receivers behind them.
                target.set(id, source != null && source.enabled && opaque ? 1f : 0f);
            }
        });
        shader.register("u_crystalProjection", new BaseShader.LocalSetter() {
            @Override public void set(BaseShader target, int id, Renderable part, Attributes attributes) {
                var source = of(attributes);
                if (source != null) { target.set(id, source.projection); }
            }
        });
        shader.register("u_crystalTransmission", new BaseShader.LocalSetter() {
            @Override public void set(BaseShader target, int id, Renderable part, Attributes attributes) {
                var source = of(attributes);
                if (source != null) { target.set(id, source.texture()); }
            }
        });
    }

    @Override public void dispose() {
        if (target != null) { target.dispose(); }
        if (shader != null) { shader.dispose(); }
        if (white != null) { white.dispose(); }
    }
}
