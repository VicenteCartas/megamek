/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.model.MeshPart;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

/** Ruby absorption along the sun's rays, on the nearest opaque receiver in the existing shadow map. */
final class GpuCrystalTransmission implements Disposable {
    /** The authored ruby material carries optical transmission independently of alpha blending. */
    static boolean ruby(Material material) { return "crystal".equals(material.id); }

    /** Closed convex primitives retain their actual facet planes for internal optical transport. */
    private static final Map<Mesh, Map<Long, float[]>> VOLUMES = new WeakHashMap<>();
    static float[] planes(MeshPart part) {
        long key = ((long) part.offset << 32) | part.size;
        return VOLUMES.computeIfAbsent(part.mesh, ignored -> new HashMap<>()).computeIfAbsent(key, ignored -> {
            Mesh mesh = part.mesh;
            int stride = mesh.getVertexSize() / Float.BYTES;
            int offset = mesh.getVertexAttribute(VertexAttributes.Usage.Position).offset / Float.BYTES;
            float[] vertices = new float[mesh.getNumVertices() * stride];
            mesh.getVertices(vertices);
            short[] indices = new short[part.size];
            mesh.getIndices(part.offset, part.size, indices, 0);
            var centre = new Vector3();
            for (short index : indices) {
                int at = Short.toUnsignedInt(index) * stride + offset;
                centre.add(vertices[at], vertices[at + 1], vertices[at + 2]);
            }
            centre.scl(1f / indices.length);
            var planes = new ArrayList<float[]>();
            var a = new Vector3(); var b = new Vector3(); var c = new Vector3();
            for (int i = 0; i < indices.length; i += 3) {
                Vector3[] triangle = { a, b, c };
                for (int j = 0; j < 3; j++) {
                    int at = Short.toUnsignedInt(indices[i + j]) * stride + offset;
                    triangle[j].set(vertices[at], vertices[at + 1], vertices[at + 2]);
                }
                b.sub(a); c.sub(a);
                float edge = Math.max(b.len2(), c.len2());
                var normal = b.crs(c);
                // Clipping can leave a microscopic triangle along an existing edge. Its float normal is unstable;
                // the neighbouring full facet already defines this optical boundary.
                if (normal.len2() < Math.max(1e-12f, edge * edge * 1e-10f)) { continue; }
                normal.nor();
                float distance = normal.dot(a);
                if (normal.dot(centre) > distance) { normal.scl(-1); distance = -distance; }
                boolean found = false;
                for (float[] plane : planes) {
                    if (normal.x * plane[0] + normal.y * plane[1] + normal.z * plane[2] > .99999f
                          && Math.abs(distance - plane[3]) < .002f) { found = true; break; }
                }
                if (!found) { planes.add(new float[] { normal.x, normal.y, normal.z, distance }); }
            }
            if (planes.size() > 32) { throw new IllegalArgumentException("Ruby primitive exceeds 32 optical facets: " + part.id); }
            for (float[] plane : planes) {
                for (short index : indices) {
                    int at = Short.toUnsignedInt(index) * stride + offset;
                    if (plane[0] * vertices[at] + plane[1] * vertices[at + 1] + plane[2] * vertices[at + 2] > plane[3] + .005f) {
                        throw new IllegalArgumentException("Ruby optical primitive must be convex: " + part.id);
                    }
                }
            }
            float[] packed = new float[planes.size() * 4];
            for (int i = 0; i < planes.size(); i++) { System.arraycopy(planes.get(i), 0, packed, i * 4, 4); }
            return packed;
        });
    }

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
            // Made at a draw's first sampler setter (an editor keeps the stage without crystals): on unit 0, which
            // ModelBatch's binder never assigns, so the units it has already bound stay intact.
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
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
