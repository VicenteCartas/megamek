/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;

/** Interpolates authored liquid frames in place; the fallback displays each frame for its original duration. */
final class GpuLiquidShader {
    /** Set false and rebuild to use the original GIF frame timing without interpolation. */
    static final boolean USE_SHADER_ANIMATION = true;

    /** The next frame, and how far the surface has blended towards it. */
    static final class Frame extends TextureAttribute {
        static final long TYPE = register("boardLiquidNextFrame");

        static { Mask |= TYPE; }

        float blend;

        Frame(Texture texture) { super(TYPE, texture); }

        private Frame(Frame copyFrom) {
            super(copyFrom);
            blend = copyFrom.blend;
        }

        @Override
        public Frame copy() { return new Frame(this); }

        @Override
        public int hashCode() { return 31 * super.hashCode() + Float.floatToRawIntBits(blend); }

        @Override
        public int compareTo(com.badlogic.gdx.graphics.g3d.Attribute other) {
            int order = super.compareTo(other);
            if (order != 0 || type != other.type) { return order; }
            return Float.compare(blend, ((Frame) other).blend);
        }
    }

    private GpuLiquidShader() { }

    static String fragment(String source) {
        String functions = GpuShaderSource.read("liquid-animation.glsl");
        // Ice wraps the original surface in a helper. Its authored samples must stay in that helper's scope.
        String entry = source.contains("void iceBaseSurface() {") ? "void iceBaseSurface() {" : "void main() {";
        // Replace the sampler, retaining its coordinates: model surfaces may apply a parallax shift before reading.
        // Matching only the old unshifted expression silently left those models on the first animation frame.
        return source.replace("texture(u_diffuseTexture, ", "liquidSample(")
              .replace("texture(u_emissiveTexture, ", "liquidSample(")
              .replace(entry, functions + "\n" + entry);
    }

    static void register(DefaultShader shader) {
        shader.register("u_liquidNextFrame", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                TextureAttribute frame = attributes.get(TextureAttribute.class, Frame.TYPE);
                if (frame != null) { target.set(id, frame.textureDescription); }
            }
        });
        shader.register("u_liquidBlend", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                Frame frame = attributes.get(Frame.class, Frame.TYPE);
                if (frame != null) { target.set(id, frame.blend); }
            }
        });
    }
}
