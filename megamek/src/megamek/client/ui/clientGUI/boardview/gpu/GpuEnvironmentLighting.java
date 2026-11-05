/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;

/** Preintegrated GGX for the board's analytic sky and ground environment, without another scene render. */
final class GpuEnvironmentLighting extends Attribute implements Disposable {
    static final long TYPE = register("boardEnvironmentLighting");
    static final int SIZE = 128;
    static final int WIDTH = SIZE * 2;
    static final int HEIGHT = SIZE + 1;
    /** Physical apparent solar radius; the moon uses the same angular source footprint. */
    static final float SOURCE_RADIUS_SINE = (float) Math.sin(Math.toRadians(.266));
    /**
     * Geometric specular anti-aliasing (light-model.glsl); the tuning's Display page sets it for the window. It is
     * compiled in: a lighting takes it as {@link #antialiasing} when its environment applies it (applyAntialiasing).
     */
    static boolean specularAntialiasing = true;
    /** The {@link #flags} bits: the features below. */
    static final int CLOUD_SHADOW = 1, AMBIENT_OCCLUSION = 2, LAVA_LIGHTING = 4, BIOMES = 8, ANTIALIASING = 16,
          CRYSTAL_TRANSMISSION = 32, NORMAL_QUALITY = 64;
    private final Color sky = new Color(), horizon = new Color();
    private Texture texture;
    private GpuLightingEnvironment image;
    private float shadowStrength = 1, imageGain = 1;
    private float daylight = 1;
    /** The board's sky occlusion by its relief (borrowed), else this lighting's own neutral one (owned, made on use). */
    private GpuSkyVisibility skyVisibility;
    private GpuSkyVisibility neutralSky;
    /** A copy's original, whose GL resources it uses (copy). */
    private GpuEnvironmentLighting original;
    /*
     * The environment's features that its lit programs compile in or read. They are fields of the lighting, not
     * attribute types of their own: libGDX has 64 types. A program's key is the environment's mask with these flags
     * (flags), so a switch is still a lighting change behind the loading stage.
     */
    /** Borrowed cloud transmission (GpuClouds owns it): cloudShadowFlag. */
    GpuCloudShadow cloudShadow;
    /** The view's ambient occlusion (borrowed): ambientOcclusionFlag (GpuAmbientOcclusion.prefix). */
    GpuAmbientOcclusion ambientOcclusion;
    /** The board's local lights (borrowed from its terrain): uniforms only. */
    GpuLavaLighting lavaLighting;
    GpuCrystalTransmission crystals;
    /** The board has biomes (GpuBiomeSurface.mark): biomeMaskFlag. */
    boolean biomes;
    /** Specular anti-aliasing as last applied (applyAntialiasing): specularAntialiasingFlag. */
    boolean antialiasing;
    /** {@link BoardQuality#cheaperShading} as last applied (applyAntialiasing): normalQualityFlag. */
    boolean cheaperShading;

    GpuEnvironmentLighting() { super(TYPE); }

    /** The features this lighting carries, as a program key's part beside its environment's mask. */
    int flags() {
        return (cloudShadow != null ? CLOUD_SHADOW : 0) | (ambientOcclusion != null ? AMBIENT_OCCLUSION : 0)
              | (lavaLighting != null ? LAVA_LIGHTING : 0) | (biomes ? BIOMES : 0) | (antialiasing ? ANTIALIASING : 0)
              | (crystals != null ? CRYSTAL_TRANSMISSION : 0) | (cheaperShading ? NORMAL_QUALITY : 0);
    }

    /** An environment's (or a draw's combined attributes') lighting, or null. */
    static GpuEnvironmentLighting of(Attributes attributes) {
        return attributes == null ? null : attributes.get(GpuEnvironmentLighting.class, TYPE);
    }

    /** The lighting of an environment that must have one, as every environment with these features does. */
    static GpuEnvironmentLighting required(Attributes environment) {
        var lighting = of(environment);
        if (lighting == null) { throw new IllegalStateException("The environment has no GpuEnvironmentLighting"); }
        return lighting;
    }

    /** The features of an environment's lighting, 0 without one (flags). */
    static int flags(Attributes environment) {
        var lighting = of(environment);
        return lighting == null ? 0 : lighting.flags();
    }

    static GpuCloudShadow cloudShadow(Attributes attributes) {
        var lighting = of(attributes);
        return lighting == null ? null : lighting.cloudShadow;
    }

    static GpuAmbientOcclusion ambientOcclusion(Attributes attributes) {
        var lighting = of(attributes);
        return lighting == null ? null : lighting.ambientOcclusion;
    }

    static GpuLavaLighting lavaLighting(Attributes attributes) {
        var lighting = of(attributes);
        return lighting == null ? null : lighting.lavaLighting;
    }

    /**
     * Gives a warm-up or rebuild sample's environment (a new environment set from the live one) a copy of its
     * lighting, so the program it builds keeps the features it was requested with, even if the live lighting changes
     * meanwhile.
     */
    static void freeze(Environment environment) {
        var lighting = of(environment);
        if (lighting != null) { environment.set(lighting.copy()); }
    }

    /** The board's sky visibility, which its owner keeps and disposes; the lit programs read it with this lighting. */
    void setSkyVisibility(GpuSkyVisibility sky) { skyVisibility = sky; }

    GpuSkyVisibility skyVisibility() {
        if (skyVisibility != null) { return skyVisibility; }
        if (original != null) { return original.skyVisibility(); }
        if (neutralSky == null) { neutralSky = new GpuSkyVisibility(); }
        return neutralSky;
    }

    /**
     * Brings the lit programs of an environment with this lighting in line with the settings
     * (specularAntialiasingFlag and normalQualityFlag, GpuCloudShadow.prefix). They are a program key's features
     * (flags), so a switch is a lighting change: shader selection, the terrain's material readiness and its family
     * programs follow it, and the terrain rebuilds behind its loading stage.
     */
    static void applyAntialiasing(Environment environment) {
        var lighting = of(environment);
        if (lighting != null) {
            lighting.antialiasing = specularAntialiasing;
            lighting.cheaperShading = BoardQuality.current.cheaperShading;
        }
    }

    void set(BoardAtmosphere.Lighting lighting) {
        sky.set(lighting.environmentSky()); horizon.set(lighting.environmentHorizon());
        shadowStrength = lighting.shadowStrength(); imageGain = lighting.environmentGain();
        daylight = lighting.daylight();
        if (image != lighting.environmentImage()) {
            image = lighting.environmentImage();
            if (texture != null) { texture.dispose(); texture = null; texture(); }
        }
    }

    private FloatBuffer pixels(int width, int height) {
        FloatBuffer pixels = arrayPixels(width, height);
        if (image != null) { image.write(pixels, width); }
        return pixels;
    }

    /** Uniform upper-hemisphere radiance has the same E/pi as this diffuse ambient color. */
    void set(Color ambient) { sky.set(ambient); horizon.set(ambient); daylight = 1; }

    /** The atmosphere's daylight share, retained with this render snapshot. */
    float daylight() { return daylight; }

    /** Copy the actual sky uniforms into a view's owned cache snapshot without exposing mutable colors. */
    void appendColors(FloatArray values) {
        values.add(sky.r, sky.g, sky.b);
        values.add(horizon.r, horizon.g, horizon.b);
        values.add(shadowStrength, imageGain);
        values.add(daylight);
        // The cache already compares floating-point snapshots; encode identity losslessly as two 16-bit words.
        int identity = System.identityHashCode(image);
        values.add(identity & 65535, identity >>> 16);
    }

    /** The atlas, made with the sky visibility's volume when a program is built, before its setters bind textures. */
    Texture texture() {
        if (original != null) { return original.texture(); }
        if (GpuSkyVisibility.STRENGTH > 0) { skyVisibility().texture(); }
        if (texture == null) {
            texture = GpuColorTexture.linear(pixels(GpuLightingEnvironment.ATLAS_SIZE, GpuLightingEnvironment.ATLAS_SIZE),
                  GpuLightingEnvironment.ATLAS_SIZE, GpuLightingEnvironment.ATLAS_SIZE, false);
            texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        }
        return texture;
    }

    /** The lighting image for a backdrop that shows it: the atlas and gain the lit surfaces sample. */
    void bindImage(ShaderProgram shader, int unit) {
        // Creating the atlas binds it on the active unit: select this unit first, so it never replaces another sampler.
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0 + unit);
        texture().bind(unit);
        shader.setUniformi("u_environmentLut", unit);
        shader.setUniformf("u_environmentImage", image == null ? 0 : image.generated ? 2 : 1, imageGain);
    }

    static void register(DefaultShader shader) {
        GpuCrystalTransmission.register(shader);
        GpuSkyVisibility.register(shader);
        shader.register("u_shadowStrength", new BaseShader.LocalSetter() {
            @Override public void set(BaseShader target, int id, Renderable part, Attributes attributes) {
                var light = attributes.get(GpuEnvironmentLighting.class, TYPE);
                target.set(id, light == null ? 1f : light.shadowStrength);
            }
        });
        shader.register("u_environmentImage", new BaseShader.LocalSetter() {
            @Override public void set(BaseShader target, int id, Renderable part, Attributes attributes) {
                var light = attributes.get(GpuEnvironmentLighting.class, TYPE);
                target.set(id, light == null || light.image == null ? 0f : light.image.generated ? 2f : 1f,
                      light == null ? 1f : light.imageGain);
            }
        });
        shader.register("u_directionalRadiusSine", new BaseShader.GlobalSetter() {
            @Override public void set(BaseShader target, int id, Renderable part, Attributes attributes) {
                target.set(id, SOURCE_RADIUS_SINE);
            }
        });
        shader.register("u_environmentLut", new BaseShader.LocalSetter() {
            @Override public void set(BaseShader target, int id, Renderable part, Attributes attributes) {
                var light = attributes.get(GpuEnvironmentLighting.class, TYPE);
                if (light != null) { target.set(id, light.texture()); }
            }
        });
        shader.register("u_environmentSky", new BaseShader.LocalSetter() {
            @Override public void set(BaseShader target, int id, Renderable part, Attributes attributes) {
                var light = attributes.get(GpuEnvironmentLighting.class, TYPE);
                if (light != null) { target.set(id, light.sky.r, light.sky.g, light.sky.b); }
            }
        });
        shader.register("u_environmentHorizon", new BaseShader.LocalSetter() {
            @Override public void set(BaseShader target, int id, Renderable part, Attributes attributes) {
                var light = attributes.get(GpuEnvironmentLighting.class, TYPE);
                if (light != null) { target.set(id, light.horizon.r, light.horizon.g, light.horizon.b); }
            }
        });
    }

    /** Native-size table in the atlas's corner, padded at its edges; shader lookups explicitly use mip zero. */
    static FloatBuffer arrayPixels(int width, int height) {
        if (width < WIDTH || height < HEIGHT) { throw new IllegalArgumentException("Environment LUT layer is too small"); }
        var pixels = BufferUtils.newFloatBuffer(width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int at = (Math.min(y, HEIGHT - 1) * WIDTH + Math.min(x, WIDTH - 1)) * 4;
                pixels.put(Table.PIXELS, at, 4);
            }
        }
        pixels.flip();
        return pixels;
    }

    private static final class Table { private static final float[] PIXELS = load(); }

    /** Generated by scripts/GenerateEnvironmentLighting.java; no numerical integration on the GL thread. */
    private static float[] load() {
        var resource = GpuEnvironmentLighting.class.getResourceAsStream("environment-lighting.bin");
        if (resource == null) { throw new IllegalStateException("Missing environment-lighting.bin"); }
        try (var input = new DataInputStream(new BufferedInputStream(resource))) {
            if (input.readInt() != 0x454C5554 || input.readInt() != WIDTH || input.readInt() != HEIGHT
                  || input.readFloat() != SOURCE_RADIUS_SINE) {
                throw new IOException("Environment LUT dimensions or source radius do not match the renderer");
            }
            float[] result = new float[WIDTH * HEIGHT * 4];
            for (int i = 0; i < result.length; i++) { result[i] = input.readFloat(); }
            if (input.read() != -1) { throw new IOException("Unexpected trailing environment LUT data"); }
            return result;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load environment-lighting.bin", failure);
        }
    }

    /** The lighting as it is now, features included; its atlas and sky volume stay this lighting's (freeze). */
    @Override public Attribute copy() {
        var copy = new GpuEnvironmentLighting(); copy.sky.set(sky); copy.horizon.set(horizon);
        copy.image = image; copy.imageGain = imageGain; copy.shadowStrength = shadowStrength;
        copy.daylight = daylight;
        copy.skyVisibility = skyVisibility;
        copy.original = original != null ? original : this;
        copy.cloudShadow = cloudShadow; copy.ambientOcclusion = ambientOcclusion; copy.lavaLighting = lavaLighting;
        copy.crystals = crystals;
        copy.biomes = biomes; copy.antialiasing = antialiasing; copy.cheaperShading = cheaperShading;
        return copy;
    }
    @Override public int hashCode() { return 31 * super.hashCode() + flags(); }
    @Override public int compareTo(Attribute other) {
        if (type != other.type) { return Long.compare(type, other.type); }
        return Integer.compare(flags(), ((GpuEnvironmentLighting) other).flags());
    }
    @Override public void dispose() {
        if (texture != null) { texture.dispose(); texture = null; }
        if (neutralSky != null) { neutralSky.dispose(); neutralSky = null; }
    }
}
