/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.Random;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLOnlyTextureData;
import com.badlogic.gdx.graphics.glutils.HdpiUtils;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.ScreenUtils;

/** Scene grading, bounded fog/shafts and wind-driven cloud shadows. Owns its GL resources. */
final class GpuAtmosphere implements Disposable {
    /** Balance between the ambient light and the spotlight (sun/moon) */
    static final float AMBIENT_LIGHT_BALANCE = 0.20f;
    /** Maximum local deviation from the shared ground-layer height, in terrain levels. */
    static final float FOG_HEIGHT_VARIATION = 1.25f;
    /** Thins the spaces between fog banks; 0 gives a uniform layer, 1 permits clear gaps. */
    static final float FOG_DENSITY_VARIATION = 0.85f;
    /** Gentle intrinsic fog travel in hex widths per second when the scenario wind is calm. */
    static final float FOG_CALM_DRIFT = 0.2f;
    /** Fog travel at full wind strength, in hex widths per second: the whole layer's speed ceiling. */
    static final float FOG_WIND_DRIFT = 0.6f;
    static final float MAX_SAND_OPACITY = 0.25f;
    /** Particle colors are authored bright, so they take this share of the light on level ground. */
    private static final float PARTICLE_ALBEDO = 0.4f;
    /**
     * Rod vision's share of dim pixels at full night (atmosphere-grade.glsl nightVision): a moonlit board, kept bright
     * enough to read, still looks like night; without a moon the eye relies on its rods more. It comes in after sunset,
     * as the night falls (nightVision()), so dusk and dawn keep their warmth.
     */
    private static final float NIGHT_VISION = .65f, MOONLESS_NIGHT_VISION = .8f;
    private static final BoardAtmosphere.SkyPalette TACTICAL_BACKDROP = BoardAtmosphere.skyPalette(
          BoardAtmosphere.DEFAULTS, BoardAtmosphere.DEFAULT_TAINT_STRENGTH, false);
    /** Below this display brightness each hex uses white neutral ink; otherwise black. Zero disables adaptation. */
    static final float OVERLAY_BRIGHTNESS_THRESHOLD = 0.04f;
    /** A learned hex changes ink only beyond this small display-brightness band around the threshold. */
    private static final float OVERLAY_BRIGHTNESS_HYSTERESIS = .02f;

    /** Local visual controls; weather and pressure still come from the scenario snapshot. */
    record Options(float rays, boolean fixedSun, float minCloudShadow, float maxCloudShadow, float sunGlare,
          float fogHeightVariation, float fogDensityVariation, float moonShadowContrast, float taintStrength, float fogCalmDrift,
          float whiteBalance, float tint, float saturation, float contrast,
          float shadowStrength, float ambientShare, float skyContribution, GpuLightingEnvironment environmentImage,
          boolean lightingSky) {
        static final Options DEFAULTS = new Options(0.5f, false);

        Options(float rays, boolean fixedSun, float minCloudShadow, float maxCloudShadow, float sunGlare,
              float fogHeightVariation, float fogDensityVariation, float moonShadowContrast, float taintStrength,
              float fogCalmDrift, float whiteBalance, float tint, float saturation, float contrast) {
            this(rays, fixedSun, minCloudShadow, maxCloudShadow, sunGlare, fogHeightVariation, fogDensityVariation,
                  moonShadowContrast, taintStrength, fogCalmDrift, whiteBalance, tint, saturation, contrast,
                  1, AMBIENT_LIGHT_BALANCE, 1, null, false);
        }

        Options(float rays, boolean fixedSun) {
            this(rays, fixedSun, GpuClouds.MIN_SHADOW_STRENGTH, GpuClouds.MAX_SHADOW_STRENGTH);
        }

        Options(float rays, boolean fixedSun, float minCloudShadow, float maxCloudShadow) {
            this(rays, fixedSun, minCloudShadow, maxCloudShadow, 0.35f);
        }

        Options(float rays, boolean fixedSun, float minCloudShadow, float maxCloudShadow, float sunGlare) {
            this(rays, fixedSun, minCloudShadow, maxCloudShadow, sunGlare, FOG_HEIGHT_VARIATION, FOG_DENSITY_VARIATION);
        }

        Options(float rays, boolean fixedSun, float minCloudShadow, float maxCloudShadow, float sunGlare,
              float fogHeightVariation, float fogDensityVariation) {
            this(rays, fixedSun, minCloudShadow, maxCloudShadow, sunGlare, fogHeightVariation, fogDensityVariation,
                  BoardAtmosphere.MOONLIGHT_SHADOW_CONTRAST);
        }

        Options(float rays, boolean fixedSun, float minCloudShadow, float maxCloudShadow, float sunGlare,
              float fogHeightVariation, float fogDensityVariation, float moonShadowContrast) {
            this(rays, fixedSun, minCloudShadow, maxCloudShadow, sunGlare, fogHeightVariation, fogDensityVariation,
                  moonShadowContrast, BoardAtmosphere.DEFAULT_TAINT_STRENGTH);
        }

        Options(float rays, boolean fixedSun, float minCloudShadow, float maxCloudShadow, float sunGlare,
              float fogHeightVariation, float fogDensityVariation, float moonShadowContrast, float taintStrength) {
            this(rays, fixedSun, minCloudShadow, maxCloudShadow, sunGlare, fogHeightVariation, fogDensityVariation,
                  moonShadowContrast, taintStrength, FOG_CALM_DRIFT);
        }

        Options(float rays, boolean fixedSun, float minCloudShadow, float maxCloudShadow, float sunGlare,
              float fogHeightVariation, float fogDensityVariation, float moonShadowContrast, float taintStrength, float fogCalmDrift) {
            this(rays, fixedSun, minCloudShadow, maxCloudShadow, sunGlare, fogHeightVariation, fogDensityVariation,
                  moonShadowContrast, taintStrength, fogCalmDrift, 0, 0, 1, 1);
        }

        Options {
            if (!Float.isFinite(rays) || !Float.isFinite(minCloudShadow) || !Float.isFinite(maxCloudShadow)
                  || !Float.isFinite(sunGlare) || !Float.isFinite(fogHeightVariation) || !Float.isFinite(fogDensityVariation)
                  || !Float.isFinite(moonShadowContrast) || !Float.isFinite(taintStrength) || !Float.isFinite(fogCalmDrift)
                  || !Float.isFinite(whiteBalance) || !Float.isFinite(tint) || !Float.isFinite(saturation) || !Float.isFinite(contrast)
                  || !Float.isFinite(shadowStrength) || !Float.isFinite(ambientShare) || !Float.isFinite(skyContribution)) {
                throw new IllegalArgumentException("Atmosphere controls must be finite");
            }
            rays = MathUtils.clamp(rays, 0, 2);
            minCloudShadow = MathUtils.clamp(minCloudShadow, 0, 1);
            maxCloudShadow = MathUtils.clamp(maxCloudShadow, minCloudShadow, 1);
            sunGlare = MathUtils.clamp(sunGlare, 0, 1);
            fogHeightVariation = MathUtils.clamp(fogHeightVariation, 0, 4);
            fogDensityVariation = MathUtils.clamp(fogDensityVariation, 0, 1);
            moonShadowContrast = MathUtils.clamp(moonShadowContrast, 0, 1);
            taintStrength = MathUtils.clamp(taintStrength, 0, 10);
            fogCalmDrift = MathUtils.clamp(fogCalmDrift, 0, 2);
            whiteBalance = MathUtils.clamp(whiteBalance, -1, 1);
            tint = MathUtils.clamp(tint, -1, 1);
            saturation = MathUtils.clamp(saturation, 0, 2);
            contrast = MathUtils.clamp(contrast, .5f, 1.5f);
            shadowStrength = MathUtils.clamp(shadowStrength, 0, 1);
            ambientShare = MathUtils.clamp(ambientShare, 0, 1);
            skyContribution = MathUtils.clamp(skyContribution, 0, 2);
        }

        float cloudShadowStrength(float cover) {
            return cover <= 0 ? 0 : MathUtils.lerp(minCloudShadow, maxCloudShadow, MathUtils.clamp(cover, 0, 1));
        }
    }

    private final Mesh quad;
    private ShaderProgram fogShader;
    private ShaderProgram compositeShader;
    /** A nuclear flash for the next composite (setFlash); NONE without one. */
    private GpuArtilleryBlast.Flash flash = GpuArtilleryBlast.Flash.NONE;
    private final Vector3 flashScreen = new Vector3();
    private FrameBuffer sceneColor;
    /** Disposable presentation history: R is binary ink, G says an in-viewport sample initialized it. */
    private FrameBuffer contrastRead;
    private FrameBuffer contrastWrite;
    private Mesh contrastPoints;
    private ShaderProgram contrastShader;
    private List<BoardScene.Tile> contrastTiles;
    private int contrastBoardId;
    private boolean contrastPrepared;
    private boolean contrastInvalidated;
    private final float contrastThreshold;
    private final float contrastDark;
    private final float contrastLight;
    private Texture sceneDepth;
    /** The window's physical size; the scene is smaller only at a window's render resolution below 1 (resampled). */
    private int displayWidth = 1;
    private int displayHeight = 1;
    private boolean resampled;
    /** The window's SMAA after the composite, made while enabled; offscreen targets never use it. */
    private boolean antiAliasing;
    private GpuSmaa smaa;
    /** Scales a resampled window's composite up to its pixels. */
    private GpuFsr fsr;
    private FrameBuffer fog;
    private FrameBuffer weatherLayer;
    private boolean weatherDrawn;
    private GpuHeatGlow heatGlow;
    /** Made at the first frame with depth cues; strength 0 skips its pass. */
    private GpuDepthCues depthCues;
    private float depthCueStrength;
    /** The lens vignette on screen (Display tab); a print never takes it. */
    private boolean vignette;
    private final Color depthHaze = new Color();
    /** This frame's seconds, as begin() received them. */
    private float frameDelta;
    private List<BoardScene.Tile> heatTiles;
    private boolean molten;
    private GpuWeatherParticles particles;
    private GpuClouds clouds;
    private boolean cloudsActive;
    private BoardAtmosphere.Settings settings;
    /** Cached world lighting and its render-owned camera-relative result; neither is game state. */
    private BoardAtmosphere.Lighting worldLighting;
    private BoardAtmosphere.Lighting lighting;
    private BoardAtmosphere.Lighting exposureLighting;
    private BoardAtmosphere.Lighting tacticalLighting;
    /** Derived together with the rendered light; the space backdrop uses the same camera coupling. */
    private boolean cameraRelativeLight;
    /** One CPU image per current atmosphere; unrelated exposure/weather/camera changes reuse it. */
    private BoardLightingSky.Source generatedSkySource;
    private GpuLightingEnvironment generatedSky;
    private BoardAtmosphere.SkyPalette backdrop;
    /** Render-owned derived backdrop, rescanned only when the immutable terrain snapshot changes. */
    private List<BoardScene.Tile> backdropTiles;
    private boolean spaceBackdrop;
    /** Animation seconds, shared by both light modes; shader constants own drift and density. No weather-clock wrap. */
    private double spaceTime;
    private final Vector3 glareRight = new Vector3();
    private float clock;
    private float stormClock;
    private Options options = Options.DEFAULTS;
    /** Derived on control changes; zero offsets leave the existing display conversion unchanged. */
    private final Vector3 whiteBalanceShift = new Vector3();
    private boolean transparentBackground;
    /** A print tile's place in its whole picture (uv offset and scale) and that picture's aspect; null on screen. */
    private float[] printFrame;
    private List<BoardScene.Tile> groundTiles;
    private float groundLevel;
    private float groundBase;
    private float sandBase;
    private float groundHollow;
    private Texture groundNoise;
    private final GroundMotion groundMotion = new GroundMotion();
    private final Vector3 groundProjection = new Vector3();
    private final float[] layerScreenBounds = new float[4];

    /** Render-owned integrated travel in periodic noise cells, shared by both board cameras. */
    static final class GroundMotion {
        final Vector2 fog = new Vector2();
        final Vector2 sand = new Vector2();
        float grains;
        private double fogSeconds;

        void advance(BoardAtmosphere.Effects effects, float delta) {
            advance(effects, delta, FOG_CALM_DRIFT);
        }

        void advance(BoardAtmosphere.Effects effects, float delta, float calmDrift) {
            float elapsed = Float.isFinite(delta) ? MathUtils.clamp(delta, 0, 0.1f) : 0;
            float x = MathUtils.sinDeg(effects.windDirection()), y = MathUtils.cosDeg(effects.windDirection());
            // Slowly bending calm flow, blended into the chosen wind vector by light-gale strength.
            // Integrate at the frame midpoint, in the existing noise coordinates: no new shader work.
            float angle = 35 + 30 * (float) Math.sin((fogSeconds + elapsed * 0.5) * Math.PI * 2 / 48);
            fogSeconds = (fogSeconds + elapsed) % 48;
            float windBlend = MathUtils.clamp(effects.wind() / 0.2f, 0, 1);
            float fogX = MathUtils.lerp(MathUtils.sinDeg(angle) * calmDrift, x * effects.wind() * FOG_WIND_DRIFT, windBlend);
            float fogY = MathUtils.lerp(MathUtils.cosDeg(angle) * calmDrift, y * effects.wind() * FOG_WIND_DRIFT, windBlend);
            move(fog, fogX, fogY, elapsed * 0.5f);
            float travel = elapsed * (0.65f + 1.5f * effects.wind());
            move(sand, x, y, travel * 0.5f);
            grains = wrap(grains + travel * 24);
        }

        private static void move(Vector2 offset, float x, float y, float distance) {
            offset.set(wrap(offset.x + x * distance), wrap(offset.y + y * distance));
        }

        private static float wrap(float value) {
            return value - (float) Math.floor(value / 256) * 256;
        }
    }

    GpuAtmosphere() { this(OVERLAY_BRIGHTNESS_THRESHOLD); }

    /**
     * Starting with the board's own {@code settings} and {@code options}, as a board view's first frame configures it:
     * its sky is generated once, not first for the defaults.
     */
    GpuAtmosphere(BoardAtmosphere.Settings settings, Options options) {
        this(OVERLAY_BRIGHTNESS_THRESHOLD, settings, options);
    }

    /** The same constructor path also permits a native check of the zero-threshold bypass. */
    GpuAtmosphere(float brightnessThreshold) {
        this(brightnessThreshold, BoardAtmosphere.DEFAULTS, Options.DEFAULTS);
    }

    private GpuAtmosphere(float brightnessThreshold, BoardAtmosphere.Settings settings, Options options) {
        contrastThreshold = GpuColor.linear(brightnessThreshold);
        contrastDark = GpuColor.linear(Math.max(0, brightnessThreshold - OVERLAY_BRIGHTNESS_HYSTERESIS));
        contrastLight = GpuColor.linear(brightnessThreshold + OVERLAY_BRIGHTNESS_HYSTERESIS);
        fogShader = GpuShaderManager.program(() -> shader("atmosphere-fog.frag"), next -> fogShader = next);
        try {
            compositeShader = GpuShaderManager.program(() -> shader("atmosphere-composite.frag"), next -> compositeShader = next);
        } catch (RuntimeException failure) {
            GpuShaderManager.dispose(fogShader);
            throw failure;
        }
        quad = screenQuad();
        // Options first: the sky they shape (moon shadow contrast, taint) is generated by configure.
        setOptions(options);
        configure(settings);
    }

    static ShaderProgram shader(String fragment) {
        String source = fragment(fragment);
        String vertex = GpuShaderSource.read("atmosphere.vert");
        String prefix = "";
        if (source.contains("// SUN_VISIBILITY")) {
            var visibility = java.util.Map.of("SUN_VISIBILITY", "sun-visibility.glsl");
            vertex = GpuShaderSource.expand(vertex, visibility);
            source = GpuShaderSource.expand(source, visibility);
            var units = BufferUtils.newIntBuffer(1);
            Gdx.gl.glGetIntegerv(GL20.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS, units);
            if (units.get(0) > 0) { prefix = "#define VERTEX_SUN_VISIBILITY\n"; }
        }
        return GpuGlsl.compile("GPU atmosphere " + fragment, prefix, vertex, source);
    }

    static String fragment(String file) {
        String source = GpuShaderSource.read(file);
        return file.equals("atmosphere-fog.frag") ? GpuSceneRadiance.fragment(source) : source;
    }

    /** Periodic two-channel noise, used by clouds and ground weather. The caller owns the texture. */
    static Texture noise() {
        Random random = new Random(0x4d4d434c);
        int[] values = new int[256 * 256];
        for (int i = 0; i < values.length; i++) { values[i] = random.nextInt(256); }
        Pixmap pixels = new Pixmap(256, 256, Pixmap.Format.RGBA8888);
        try {
            for (int y = 0; y < 256; y++) {
                for (int x = 0; x < 256; x++) {
                    int adjacent = values[((y + 17) % 256) * 256 + (x + 37) % 256];
                    pixels.drawPixel(x, y, (values[y * 256 + x] << 24) | (adjacent << 16) | 255);
                }
            }
            Texture result = new Texture(pixels);
            result.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            result.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
            return result;
        } finally {
            pixels.dispose();
        }
    }

    /** Shared screen-space geometry; the caller owns the returned mesh. */
    static Mesh screenQuad() {
        Mesh result = new Mesh(true, 4, 6, VertexAttribute.Position(), VertexAttribute.TexCoords(0));
        result.setVertices(new float[] { -1, -1, 0, 0, 0, 1, -1, 0, 1, 0,
              1, 1, 0, 1, 1, -1, 1, 0, 0, 1 });
        result.setIndices(new short[] { 0, 1, 2, 0, 2, 3 });
        return result;
    }

    void configure(BoardAtmosphere.Settings next) {
        if (!next.equals(settings)) {
            invalidateContrast();
            if (settings == null || settings.effects().lightning() == 0) {
                stormClock = 0;
            }
            settings = next;
            worldLighting = generateSky(BoardAtmosphere.lighting(next, options.moonShadowContrast(), options.taintStrength()));
            backdrop = BoardAtmosphere.skyPalette(next, options.taintStrength(), false);
            exposureLighting = worldLighting;
            lighting = tune(worldLighting);
        }
    }

    BoardAtmosphere.Lighting lighting() {
        return lighting;
    }

    /** The complete scene's adaptation and the scenario or tuning exposure compensation. */
    float exposure() {
        // Manual light controls must not immediately be cancelled by automatic exposure.
        return exposureLighting.adaptation() * (float) Math.pow(2, settings.exposure());
    }

    /**
     * Rod vision's share now: none while the sun is up (daylight from .6), easing in as the twilight fades, to
     * NIGHT_VISION once the daylight has gone, or MOONLESS_NIGHT_VISION without a moon.
     */
    float nightVision() {
        float night = MathUtils.clamp((.6f - lighting.daylight()) / .6f, 0, 1);
        return (settings.moonlight() ? NIGHT_VISION : MOONLESS_NIGHT_VISION) * night * night * (3 - 2 * night);
    }

    /** Linear light for smoke, spray and precipitation blended into the HDR scene. */
    Color sceneParticleLight() {
        Color ground = lighting.groundLight();
        ground.r *= PARTICLE_ALBEDO;
        ground.g *= PARTICLE_ALBEDO;
        ground.b *= PARTICLE_ALBEDO;
        return ground;
    }

    /** Apply after the camera's final pose, before either geometry shadows or cloud transmission. */
    void updateLight(Camera camera) {
        updateLight(camera, false, false);
    }

    /** Free Flight always uses the world sun; Tactical View keeps its own light. The saved option stays untouched. */
    void updateLight(BoardCamera camera) {
        updateLight(camera.camera, camera.tactical(), camera.firstPerson());
    }

    private void updateLight(Camera camera, boolean tactical, boolean freeFlight) {
        // Exposure belongs to the canonical source; moving the displayed sun must not pump its meter.
        if (tactical && tacticalLighting == null) {
            tacticalLighting = BoardAtmosphere.TACTICAL.withEnvironment(
                  GpuLightingEnvironment.generated(BoardLightingSky.generate(BoardAtmosphere.TACTICAL.skySource())));
        }
        exposureLighting = tactical ? tacticalLighting : worldLighting;
        cameraRelativeLight = options.fixedSun() && !freeFlight && !tactical;
        lighting = tune(cameraRelativeLight ? exposureLighting.relativeTo(camera) : exposureLighting);
    }

    private BoardAtmosphere.Lighting tune(BoardAtmosphere.Lighting base) {
        return base.tuned(options.shadowStrength(), options.ambientShare(), options.skyContribution(), options.environmentImage());
    }

    private BoardAtmosphere.Lighting generateSky(BoardAtmosphere.Lighting base) {
        if (!base.skySource().same(generatedSkySource)) {
            // Build the complete replacement before publishing it; no old atlas/new ambient mixture.
            generatedSky = GpuLightingEnvironment.generated(BoardLightingSky.generate(base.skySource()));
            generatedSkySource = base.skySource();
        }
        return base.withEnvironment(generatedSky);
    }

    void setOptions(Options options) {
        if (!this.options.equals(options)) { invalidateContrast(); }
        if (settings != null && (this.options.moonShadowContrast() != options.moonShadowContrast()
              || this.options.taintStrength() != options.taintStrength())) {
            worldLighting = generateSky(BoardAtmosphere.lighting(settings, options.moonShadowContrast(), options.taintStrength()));
            backdrop = BoardAtmosphere.skyPalette(settings, options.taintStrength(), false);
        }
        if (this.options.whiteBalance() != options.whiteBalance() || this.options.tint() != options.tint()) {
            // Relative cool/warm and green/magenta gains, normalized to preserve a neutral gray's luminance.
            float red = (float) Math.pow(2, .5f * options.whiteBalance() + .25f * options.tint());
            float green = (float) Math.pow(2, -.25f * options.tint());
            float blue = (float) Math.pow(2, -.5f * options.whiteBalance() + .25f * options.tint());
            float luminance = .2126f * red + .7152f * green + .0722f * blue;
            whiteBalanceShift.set(red / luminance - 1, green / luminance - 1, blue / luminance - 1);
        }
        this.options = options;
        if (worldLighting != null) {
            exposureLighting = worldLighting;
            lighting = tune(worldLighting);
        }
    }

    /** A print's cutout keeps coverage for its paper; additive light outside that coverage has no straight-alpha form. */
    void setTransparentBackground(boolean enabled) { transparentBackground = enabled; }

    /**
     * The next composites are the tile at {@code left, top} (pixels, rows downward) of a {@code width} by
     * {@code height} print: the space sky spans the whole print, and no lens vignette darkens each tile's corners.
     */
    void setPrintTile(int left, int top, int tileWidth, int tileHeight, int width, int height) {
        printFrame = new float[] { left / (float) width, (height - top - tileHeight) / (float) height,
              tileWidth / (float) width, tileHeight / (float) height, width / (float) height };
    }

    /** The backdrop: the visible sky palette, or with "Show lighting sky" the sky that lights the scene. */
    private Color backdropSky() { return options.lightingSky() ? lighting.environmentSky() : lighting.sky(); }

    private Color backdropHorizon() { return options.lightingSky() ? lighting.environmentHorizon() : lighting.horizon(); }

    boolean spaceBackdrop(BoardScene scene) {
        if (backdropTiles != scene.tiles()) {
            backdropTiles = scene.tiles();
            spaceBackdrop = backdropTiles.stream().anyMatch(tile -> tile.backdrop() == BoardBackdrop.SPACE);
        }
        return spaceBackdrop;
    }

    /** Subtract exactly the half-float clear that was stored, so adaptation cannot reveal rounding residue. */
    private static float clearComponent(float radiance) {
        return Float.float16ToFloat(Float.floatToFloat16(radiance * GpuSceneRadiance.SCALE)) / GpuSceneRadiance.SCALE;
    }

    /**
     * SMAA on the window's composite (not on offscreen targets); turning it off releases its targets and keeps its
     * programs, which the warm-up's SMAA frame links (GpuBattleView).
     */
    void setAntiAliasing(boolean enabled) {
        antiAliasing = enabled;
        if (!enabled && smaa != null) { smaa.disposeTargets(); }
    }

    /** Publish material flags before warmup, without advancing wind or rendering with an unfinished camera. */
    void configureClouds(GpuTerrain terrain, BoardScene board) {
        if (heatTiles != board.tiles()) {
            molten = molten(heatTiles, board.tiles(), molten);
            heatTiles = board.tiles();
        }
        terrain.setWetness(BoardAtmosphere.wetness(settings));
        terrain.setWind(settings.effects());
        cloudsActive = settings.clouds() > 0 && lighting.hasDirectLight();
        // Cloud cover changes uniforms, not the surface program or its preparation requirements.
        if (clouds == null) { clouds = new GpuClouds(quad); }
        clouds.shadow().enabled = cloudsActive;
        clouds.shadow().reflection = settings.clouds() > 0;
        GpuEnvironmentLighting.required(terrain.environment()).cloudShadow = clouds.shadow();
    }

    /**
     * Whether any tile is molten. The map source keeps unchanged tile objects, so an edit reads only the tiles that
     * differ by identity; the whole board only when a molten tile changed or the board was replaced.
     */
    private static boolean molten(List<BoardScene.Tile> before, List<BoardScene.Tile> after, boolean wasMolten) {
        if (before != null && before.size() == after.size()) {
            boolean lost = false;
            for (int index = 0; index < after.size(); index++) {
                if (before.get(index) == after.get(index)) { continue; }
                if (after.get(index).liquid().molten()) { return true; }
                lost |= before.get(index).liquid().molten();
            }
            if (!lost) { return wasMolten; }
        }
        return after.stream().anyMatch(tile -> tile.liquid().molten());
    }

    /** Prepare clouds after the final camera/light update; cameras share their field and wind timeline. */
    void prepareClouds(GpuTerrain terrain, BoardScene board, float delta) {
        configureClouds(terrain, board);
        if (clouds.shadow().reflection) {
            clouds.update(settings, lighting, board, delta, options.cloudShadowStrength(settings.clouds()));
        }
    }

    void begin(int width, int height, float delta) {
        begin(width, height, 1, delta);
    }

    /**
     * A render resolution {@code scale} below 1 renders the scene, its scene-sized targets, the composite and SMAA at
     * that share of the window's pixels; FSR 1 then scales them up to the window (GpuFsr).
     */
    void begin(int width, int height, float scale, float delta) {
        int pixelsWide = Math.max(1, HdpiUtils.toBackBufferX(width)), pixelsHigh = Math.max(1, HdpiUtils.toBackBufferY(height));
        beginPixels(scaled(pixelsWide, scale), scaled(pixelsHigh, scale), delta);
        displayWidth = pixelsWide;
        displayHeight = pixelsHigh;
        resampled = scale != 1;
    }

    /** The scene's pixels along one axis at a render resolution {@code scale}, never fewer than one. */
    static int scaled(int pixels, float scale) {
        return Math.max(1, Math.round(pixels * scale));
    }

    /** Offscreen exports specify physical pixels, independently of the window's display scaling. */
    void beginPixels(int pixelsWide, int pixelsHigh, float delta) {
        contrastPrepared = false;
        displayWidth = pixelsWide;
        displayHeight = pixelsHigh;
        resampled = false;
        weatherDrawn = false;
        if (sceneColor == null || sceneColor.getWidth() != pixelsWide || sceneColor.getHeight() != pixelsHigh) {
            disposeBuffers();
            // Preserve reflected highlights as well as emission until the shared exposure and display shoulder.
            // All scene surfaces and transparent effects supply linear radiance for hardware blending.
            sceneColor = GpuHeatGlow.buffer(pixelsWide, pixelsHigh);
            sceneColor.getColorBufferTexture().setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            try {
                sceneDepth = attachDepthTexture(sceneColor);
            } catch (RuntimeException failure) {
                disposeBuffers();
                throw failure;
            }
        }
        if (hasScattering() && fog == null) {
            // Scattered shafts can exceed display white; faint fog also needs precision before exposure.
            fog = GpuHeatGlow.buffer(Math.max(1, pixelsWide / 4), Math.max(1, pixelsHigh / 4));
            fog.getColorBufferTexture().setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        }
        frameDelta = delta;
        clock = (clock + Math.min(delta, 0.1f)) % 3600;
        spaceTime += Math.min(delta, .1f);
        stormClock = settings.effects().lightning() > 0 ? (stormClock + Math.min(delta, 0.1f)) % 7 : 0;
        groundMotion.advance(settings.effects(), delta, options.fogCalmDrift());
        sceneColor.begin();
        Gdx.gl.glDepthMask(true);
        // A cutout accumulates associated foreground from transparent black; it has no sky matte to remove.
        Color sky = transparentBackground ? Color.BLACK : backdropSky();
        ScreenUtils.clear(clearComponent(sky.r) * GpuSceneRadiance.SCALE, clearComponent(sky.g) * GpuSceneRadiance.SCALE,
              clearComponent(sky.b) * GpuSceneRadiance.SCALE, 0, true);
    }

    static FrameBuffer buffer(int width, int height, boolean depth) {
        GLFrameBuffer.FrameBufferBuilder builder = new GLFrameBuffer.FrameBufferBuilder(width, height);
        builder.addBasicColorTextureAttachment(Pixmap.Format.RGBA8888);
        if (depth) {
            // Small decal offsets need the same 24-bit precision as the native board window.
            builder.addDepthRenderBuffer(GL30.GL_DEPTH_COMPONENT24);
        }
        FrameBuffer result = builder.build();
        result.getColorBufferTexture().setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        return result;
    }

    /** Caller owns the returned depth texture separately from the target; uses the existing desktop GL20 context. */
    static Texture attachDepthTexture(FrameBuffer target) {
        Texture depth = new Texture(new GLOnlyTextureData(target.getWidth(), target.getHeight(), 0,
              GL30.GL_DEPTH_COMPONENT24, GL20.GL_DEPTH_COMPONENT, GL20.GL_UNSIGNED_INT));
        try {
            depth.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            depth.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
            attachDepthTexture(target, depth);
            return depth;
        } catch (RuntimeException failure) {
            depth.dispose();
            throw failure;
        } finally {
            FrameBuffer.unbind();
        }
    }

    /** Borrow an existing depth attachment; the target owns only its color texture. */
    private static void attachDepthTexture(FrameBuffer target, Texture depth) {
        target.bind();
        Gdx.gl.glFramebufferTexture2D(GL20.GL_FRAMEBUFFER, GL20.GL_DEPTH_ATTACHMENT,
              GL20.GL_TEXTURE_2D, depth.getTextureObjectHandle(), 0);
        if (Gdx.gl.glCheckFramebufferStatus(GL20.GL_FRAMEBUFFER) != GL20.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("GPU scene depth attachment is incomplete");
        }
    }

    /** Composite color and opaque depth together; the scene's geometry is submitted only once. */
    /** A nuclear flash over the next composited view: a whiteout that glares most around its fireball. */
    void setFlash(GpuArtilleryBlast.Flash next) { flash = next; }

    /** The depth cues' haze strength for the next board views ({@link GpuDepthCues}), 0 to 1; 0 is off. */
    void setDepthCues(float strength) { depthCueStrength = MathUtils.clamp(strength, 0, 1); }

    void setVignette(boolean enabled) { vignette = enabled; }

    void end(Camera camera, GpuTerrain terrain, BoardScene board, int bottom) {
        end(camera, terrain, board, bottom, null);
    }

    void end(Camera camera, GpuTerrain terrain, BoardScene board, int bottom,
          GpuFieldOfView fieldOfView) {
        end(camera, terrain, board, bottom, fieldOfView, true);
    }

    /** Wireframe suppresses source bloom and lens reflections without changing the user's glare setting. */
    void end(Camera camera, GpuTerrain terrain, BoardScene board, int bottom,
          GpuFieldOfView fieldOfView, boolean glareEnabled) {
        end(camera, terrain, board, bottom, fieldOfView, glareEnabled, null);
    }

    /** Offscreen editor samples use the board's color grading, without changing the main view's targets. */
    void end(Camera camera, GpuTerrain terrain, BoardScene board, int bottom,
          GpuFieldOfView fieldOfView, boolean glareEnabled, FrameBuffer target) {
        boolean fovActive = fieldOfView != null && fieldOfView.active();
        boolean antiAliased = target == null && antiAliasing;
        boolean upscaled = target == null && resampled;
        // Before any texture binding below: its lookup tables bind the active unit while they upload.
        if (antiAliased && smaa == null) { smaa = new GpuSmaa(quad); }
        if (upscaled && fsr == null) { fsr = new GpuFsr(quad); }
        else if (!upscaled && target == null && fsr != null) { fsr.disposeTargets(); }
        sceneColor.end();
        if (hasScattering()) {
            renderFog(camera, terrain, board);
        }
        boolean glowActive = molten;
        if (glowActive) {
            if (heatGlow == null) { heatGlow = new GpuHeatGlow(quad); }
            heatGlow.render(sceneColor.getColorBufferTexture(), sceneDepth,
                  hasScattering() ? fog.getColorBufferTexture() : null, camera, fieldOfView);
        }
        BoardProjectionCamera cued = depthCueStrength > 0 && terrain != null
              && camera instanceof BoardProjectionCamera view ? view : null;
        Texture water = hasScattering() || settings.effects().sand() > 0 || cued != null ? terrain.waterDepth() : null;
        if (cued != null) {
            if (depthCues == null) { depthCues = new GpuDepthCues(quad); }
            depthCues.render(sceneDepth, water, cued, frameDelta);
        } else if (depthCues != null) {
            depthCues.forget();
        }
        // The window's composite and SMAA run at the scene's size, which only FSR's upscale leaves.
        FrameBuffer upscale = upscaled ? fsr.input(sceneColor.getWidth(), sceneColor.getHeight()) : null;
        if (target != null) { target.begin(); }
        else if (antiAliased) { smaa.begin(sceneColor.getWidth(), sceneColor.getHeight()); }
        else if (upscale != null) { upscale.begin(); }
        else { HdpiUtils.glViewport(0, bottom, (int) camera.viewportWidth, (int) camera.viewportHeight); }
        screenState();
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_ALWAYS);
        Gdx.gl.glDepthMask(true);
        compositeShader.bind();
        sceneColor.getColorBufferTexture().bind(0);
        sceneDepth.bind(1);
        if (hasScattering()) {
            fog.getColorBufferTexture().bind(2);
        }
        compositeShader.setUniformf("u_fovEnabled", fovActive ? 1 : 0);
        if (fovActive) {
            fieldOfView.bind(compositeShader, camera);
        }
        compositeShader.setUniformi("u_scene", 0);
        // White-hot at once, warming to the fireball's orange; its glare stays round whatever the view's shape.
        float warmth = flash.warmth();
        compositeShader.setUniformf("u_flash", flash.intensity(), flash.intensity() * (.97f - .37f * warmth),
              flash.intensity() * (.9f - .62f * warmth), 7);
        camera.project(flashScreen.set(flash.centre()), 0, 0, camera.viewportWidth, camera.viewportHeight);
        boolean ahead = camera.direction.dot(flash.centre().x - camera.position.x, flash.centre().y - camera.position.y,
              flash.centre().z - camera.position.z) > 0;
        compositeShader.setUniformf("u_flashCentre", ahead ? flashScreen.x / camera.viewportWidth : -10,
              ahead ? flashScreen.y / camera.viewportHeight : -10, camera.viewportWidth / camera.viewportHeight);
        compositeShader.setUniformi("u_depth", 1);
        if (water != null) { water.bind(6); }
        compositeShader.setUniformi("u_waterDepth", water == null ? 1 : 6);
        if (cued != null) {
            depthCues.bind(compositeShader, depthCueStrength,
                  BoardAtmosphere.depthHaze(lighting, settings.pressure().isVacuum(), depthHaze), 9);
        }
        else { GpuDepthCues.unbind(compositeShader, 0); }
        compositeShader.setUniformi("u_fog", hasScattering() ? 2 : 0);
        if (glowActive) { heatGlow.texture().bind(5); }
        compositeShader.setUniformi("u_heatGlow", glowActive ? 5 : 0);
        if (weatherDrawn) { weatherLayer.getColorBufferTexture().bind(7); }
        compositeShader.setUniformi("u_weather", weatherDrawn ? 7 : 0);
        compositeShader.setUniformf("u_weatherEnabled", weatherDrawn ? 1 : 0);
        compositeShader.setUniformf("u_heatEnabled", molten ? 1 : 0);
        compositeShader.setUniformf("u_heatGlowStrength", glowActive ? 0.12f : 0);
        compositeShader.setUniformf("u_fogEnabled", hasScattering() ? 1 : 0);
        if (hasScattering()) {
            // renderFog just projected its volume. Sand may subsequently bind a different, lower volume.
            compositeShader.setUniform4fv("u_scatteringBounds", layerScreenBounds, 0, 4);
        }
        compositeShader.setUniformf("u_fogSize", hasScattering() ? fog.getWidth() : 1, hasScattering() ? fog.getHeight() : 1);
        compositeShader.setUniformf("u_projectionDepth", camera.projection.val[Matrix4.M22],
              camera.projection.val[Matrix4.M23], camera.projection.val[Matrix4.M32], camera.projection.val[Matrix4.M33]);
        compositeShader.setUniformf("u_edgeScale", BoardGeometry.level());
        compositeShader.setUniformf("u_exposure", exposure());
        compositeShader.setUniformf("u_tint", lighting.tint().r, lighting.tint().g, lighting.tint().b);
        compositeShader.setUniformf("u_saturation", lighting.saturation());
        compositeShader.setUniformf("u_nightVision", nightVision());
        compositeShader.setUniformf("u_whiteBalanceShift", whiteBalanceShift);
        compositeShader.setUniformf("u_colorAdjust", options.saturation() - 1, options.contrast() - 1);
        Color sky = backdropSky(), horizon = backdropHorizon();
        compositeShader.setUniformf("u_sky", clearComponent(sky.r), clearComponent(sky.g), clearComponent(sky.b));
        compositeShader.setUniformf("u_horizon", horizon.r, horizon.g, horizon.b);
        // The UI-authored backdrop is independent of illumination adaptation. The optional lighting-sky view
        // continues to show the actual incident environment, including its panorama and world-space horizon.
        var visible = exposureLighting == tacticalLighting ? TACTICAL_BACKDROP : backdrop;
        compositeShader.setUniformf("u_backdropSky", visible.sky().r, visible.sky().g, visible.sky().b);
        compositeShader.setUniformf("u_backdropHorizon", visible.horizon().r, visible.horizon().g, visible.horizon().b);
        compositeShader.setUniformf("u_backdropExposure", options.lightingSky() ? 0 : (float) Math.pow(2, settings.exposure()));
        // With "Show lighting sky", the lighting image fills the background as the lit surfaces see it.
        var environment = options.lightingSky() && lighting.environmentImage() != null && terrain != null
              ? terrain.environment().get(GpuEnvironmentLighting.class, GpuEnvironmentLighting.TYPE) : null;
        if (environment != null) { environment.bindImage(compositeShader, 8); }
        else { compositeShader.setUniformf("u_environmentImage", 0, 1); }
        compositeShader.setUniformf("u_transparentBackground", transparentBackground ? 1 : 0);
        compositeShader.setUniformf("u_spaceBackdrop", spaceBackdrop(board) ? 1 : 0, printFrame == null
              ? camera.viewportWidth / Math.max(1, camera.viewportHeight) : printFrame[4], cameraRelativeLight ? 1 : 0);
        if (printFrame == null) { compositeShader.setUniformf("u_frame", 0, 0, 1, 1); }
        else { compositeShader.setUniformf("u_frame", printFrame[0], printFrame[1], printFrame[2], printFrame[3]); }
        compositeShader.setUniformf("u_vignette", printFrame == null && vignette ? 1 : 0);
        // Zero drift or density constants can optimize these uniforms out of an edited shader.
        compositeShader.setUniformf(compositeShader.fetchUniformLocation("u_spaceTime", false), (float) spaceTime);
        compositeShader.setUniformMatrix(compositeShader.fetchUniformLocation("u_spaceView", false), camera.view);
        compositeShader.setUniformf(compositeShader.fetchUniformLocation("u_windowPixels", false),
              upscale == null ? 1 : displayWidth / (float) sceneColor.getWidth());
        Color direct = lighting.direct();
        float lightPeak = Math.max(direct.r, Math.max(direct.g, direct.b));
        // Lighting.direction is photon travel. The visible source is in the opposite direction, without sky drift.
        Vector3 lightDirection = lighting.direction();
        compositeShader.setUniformf("u_spaceLight", -lightDirection.x, -lightDirection.y, -lightDirection.z,
              Math.min(1, lightPeak));
        compositeShader.setUniformf("u_spaceLightColor", direct.r / Math.max(lightPeak, 1e-8f),
              direct.g / Math.max(lightPeak, 1e-8f), direct.b / Math.max(lightPeak, 1e-8f));
        compositeShader.setUniformMatrix("u_inverseView", camera.invProjectionView);
        compositeShader.setUniformf("u_groundBoard", board.width(), board.height(), BoardGeometry.width(), BoardGeometry.height());
        bindSand(camera, board);
        bindSunGlare(camera, glareEnabled);
        // Strike promptly when enabled, then at seven-second intervals, with a quick attack and longer decay.
        float attack = MathUtils.clamp((stormClock - 0.35f) / 0.06f, 0, 1);
        float decay = 1 - MathUtils.clamp((stormClock - 0.41f) / 0.54f, 0, 1);
        compositeShader.setUniformf("u_lightning", attack * decay * decay * settings.effects().lightning() * 1.1f);
        quad.render(compositeShader, GL20.GL_TRIANGLES);
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        if (target != null) { target.end(); }
        else if (antiAliased) { smaa.end(upscale, sceneDepth, camera, bottom); }
        if (upscale != null) { fsr.end(sceneDepth, camera, bottom, displayWidth, displayHeight); }
    }

    /** A distant light follows the viewing angle and perspective FOV, independently of camera position. */
    private void bindSunGlare(Camera camera, boolean enabled) {
        float aspect = camera.viewportWidth / Math.max(1, camera.viewportHeight);
        float forward = -camera.direction.dot(lighting.direction());
        // The glare takes the sun's normalized color, and its brightness up to that of a full-strength sun.
        float peak = Math.max(worldLighting.direct().r, Math.max(worldLighting.direct().g, worldLighting.direct().b));
        if (!enabled || options.sunGlare() == 0 || !lighting.sunlight() || peak <= 0 || forward <= 0.15f) {
            compositeShader.setUniformf("u_sunGlare", 0, 0, 0, aspect);
            return;
        }
        glareRight.set(camera.direction).crs(camera.up).nor();
        // Orthographic views retain the approximately 80-degree angular field used for their distant sky.
        float projection = (camera.projection.val[Matrix4.M33] == 0
              ? 2 / camera.projection.val[Matrix4.M11] : 1.7f) * forward;
        float x = 0.5f - glareRight.dot(lighting.direction()) / (projection * aspect);
        float y = 0.5f - camera.up.dot(lighting.direction()) / projection;
        float facing = MathUtils.clamp((forward - 0.15f) / 0.6f, 0, 1);
        facing *= facing * (3 - 2 * facing);
        float outside = Math.max(Math.max(-x, x - 1) * aspect, Math.max(-y, y - 1));
        float edge = 1 - MathUtils.clamp(outside / 0.25f, 0, 1);
        edge *= edge * (3 - 2 * edge);
        // Use world-source energy/elevation even in fixed-sun mode, whose surface-light compensation varies with tilt.
        float horizon = 0.35f + 0.65f * (1 - Math.abs(worldLighting.direction().z));
        float clear = 1 - settings.clouds() * (settings.pressure().isThin() ? 0.35f : 0.95f);
        float visibility = clear * clear * (1 - settings.fog() * 0.8f) * (1 - settings.haze() * 0.4f);
        compositeShader.setUniformf("u_sunGlare", x, y,
              options.sunGlare() * Math.min(1, peak) * horizon * facing * edge * visibility, aspect);
        compositeShader.setUniformf("u_glareColor", worldLighting.direct().r / peak,
              worldLighting.direct().g / peak, worldLighting.direct().b / peak);
    }

    /**
     * Evaluate the completed HDR scene once per hex, before grid/text. Pan, zoom and scene resolution changes retain
     * the learned ink. The two tiny RG8 targets are separate from the resize-dependent scene buffers.
     */
    void prepareContrast(Camera camera, BoardScene board, int bottom) {
        if (contrastThreshold <= 0 || board.tiles().isEmpty()) { return; }
        try {
            if (contrastRead == null || contrastBoardId != board.boardId() || contrastRead.getWidth() != board.width()
                  || contrastRead.getHeight() != board.height() || contrastPositionsChanged(board.tiles())) {
                resetContrast();
                if (contrastShader == null) {
                    contrastShader = GpuShaderManager.program(() -> GpuGlsl.compile("GPU hex ink history",
                          GpuShaderSource.read("hex-ink.vert"), GpuShaderSource.read("hex-ink.frag")), next -> contrastShader = next);
                }
                try {
                    contrastRead = contrastTarget(board.width(), board.height());
                    contrastWrite = contrastTarget(board.width(), board.height());
                    float[] points = new float[board.tiles().size() * 3];
                    int at = 0;
                    for (var tile : board.tiles()) {
                        points[at++] = tile.coords().getX();
                        points[at++] = tile.coords().getY();
                        points[at++] = tile.elevation();
                    }
                    contrastPoints = new Mesh(true, board.tiles().size(), 0,
                          new VertexAttribute(VertexAttributes.Usage.Generic, 3, "a_hex"));
                    contrastPoints.setVertices(points);
                    contrastBoardId = board.boardId();
                } catch (RuntimeException failure) {
                    resetContrast();
                    throw failure;
                }
            }
            contrastTiles = board.tiles();
            if (contrastInvalidated) {
                contrastRead.begin();
                screenState();
                ScreenUtils.clear(0, 0, 0, 0);
            }
            contrastWrite.begin();
            screenState();
            Gdx.gl.glEnable(org.lwjgl.opengl.GL20.GL_VERTEX_PROGRAM_POINT_SIZE);
            contrastShader.bind();
            contrastRead.getColorBufferTexture().bind(0);
            sceneColor.getColorBufferTexture().bind(1);
            contrastShader.setUniformi("u_history", 0);
            contrastShader.setUniformi("u_scene", 1);
            contrastShader.setUniformMatrix("u_projection", camera.combined);
            contrastShader.setUniformf("u_hexSize", BoardGeometry.width(), BoardGeometry.height(), BoardGeometry.level());
            contrastShader.setUniformf("u_boardSize", board.width(), board.height());
            contrastShader.setUniformf("u_thresholds", contrastThreshold, contrastDark, contrastLight);
            contrastShader.setUniformf("u_exposure", exposure());
            Color clear = spaceBackdrop(board) && !transparentBackground ? backdropSky() : Color.BLACK;
            contrastShader.setUniformf("u_spaceClear", clearComponent(clear.r) * GpuSceneRadiance.SCALE,
                  clearComponent(clear.g) * GpuSceneRadiance.SCALE, clearComponent(clear.b) * GpuSceneRadiance.SCALE);
            contrastPoints.render(contrastShader, GL20.GL_POINTS);
            FrameBuffer previous = contrastRead;
            contrastRead = contrastWrite;
            contrastWrite = previous;
            contrastPrepared = true;
            contrastInvalidated = false;
        } finally {
            Gdx.gl.glDisable(org.lwjgl.opengl.GL20.GL_VERTEX_PROGRAM_POINT_SIZE);
            FrameBuffer.unbind();
            HdpiUtils.glViewport(0, bottom, (int) camera.viewportWidth, (int) camera.viewportHeight);
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
            Gdx.gl.glDepthMask(true);
        }
    }

    private boolean contrastPositionsChanged(List<BoardScene.Tile> next) {
        if (contrastTiles == next) { return false; }
        if (contrastTiles == null || contrastTiles.size() != next.size()) { return true; }
        for (int i = 0; i < next.size(); i++) {
            var before = contrastTiles.get(i);
            var after = next.get(i);
            if (!before.coords().equals(after.coords()) || before.elevation() != after.elevation()) { return true; }
        }
        return false;
    }

    private static FrameBuffer contrastTarget(int width, int height) {
        var builder = new GLFrameBuffer.FrameBufferBuilder(width, height);
        builder.addColorTextureAttachment(GL30.GL_RG8, GL30.GL_RG, GL20.GL_UNSIGNED_BYTE);
        FrameBuffer result = builder.build();
        try {
            result.getColorBufferTexture().setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            result.getColorBufferTexture().setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
            result.begin();
            ScreenUtils.clear(0, 0, 0, 0);
            return result;
        } catch (RuntimeException failure) {
            result.dispose();
            throw failure;
        }
    }

    /** Defer the GL clear until the next completed scene; preserve the owner mesh and both small targets. */
    private void invalidateContrast() {
        contrastInvalidated = true;
        contrastPrepared = false;
    }

    /** A same-sized board replacement is explicit; style/label edits and window resizes do not clear history. */
    void resetContrast() {
        if (contrastRead != null) { contrastRead.dispose(); contrastRead = null; }
        if (contrastWrite != null) { contrastWrite.dispose(); contrastWrite = null; }
        if (contrastPoints != null) { contrastPoints.dispose(); contrastPoints = null; }
        contrastTiles = null;
        contrastPrepared = false;
        contrastInvalidated = false;
    }

    /** Bind only a prepared ink atlas. A zero threshold never allocates, updates or binds contrast resources. */
    void bindContrast(ShaderProgram shader, int textureUnit) {
        boolean enabled = contrastThreshold > 0 && contrastPrepared;
        shader.setUniformf("u_contrastEnabled", enabled ? 1 : 0);
        if (!enabled) { return; }
        contrastRead.getColorBufferTexture().bind(textureUnit);
        shader.setUniformi("u_contrastInk", textureUnit);
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
    }

    /** Borrowed hardware camera depth in the red channel, valid after end(). */
    Texture depthTexture() {
        return sceneDepth;
    }

    private boolean hasFog() {
        return settings.fog() > 0 || settings.haze() > 0;
    }

    private boolean hasRays() {
        return cloudsActive && lighting.sunlight() && options.rays() > 0;
    }

    private boolean hasScattering() {
        return hasFog() || hasRays();
    }

    private void updateGroundBase(BoardScene board) {
        if (groundTiles != board.tiles() || groundLevel != BoardGeometry.level()) {
            groundTiles = board.tiles();
            groundLevel = BoardGeometry.level();
            groundBase = BoardGeometry.weatherBase(board);
            sandBase = BoardGeometry.sandBase(board);
            groundHollow = (float) board.tiles().stream().mapToDouble(BoardRelief::headroom).max().orElse(0);
        }
    }

    private void bindGroundLayer(ShaderProgram shader, Camera camera, BoardScene board, float top, int noiseUnit) {
        shader.setUniformMatrix("u_inverseView", camera.invProjectionView);
        shader.setUniformf("u_groundBoard", board.width(), board.height(), BoardGeometry.width(), BoardGeometry.height());
        shader.setUniformf("u_groundHollow", groundHollow);
        shader.setUniformf("u_boundsMin", -BoardGeometry.width(), -(board.height() + 1) * BoardGeometry.height(),
              groundBase);
        shader.setUniformf("u_boundsMax", (board.width() + 1) * BoardGeometry.width() * 0.75f, BoardGeometry.height(), top);
        layerScreenBounds[0] = Float.POSITIVE_INFINITY;
        layerScreenBounds[1] = Float.POSITIVE_INFINITY;
        layerScreenBounds[2] = Float.NEGATIVE_INFINITY;
        layerScreenBounds[3] = Float.NEGATIVE_INFINITY;
        // Projected corners bound a convex volume while it lies entirely in front of the near plane.
        for (int corner = 0; corner < 8; corner++) {
            groundProjection.set((corner & 1) == 0 ? -BoardGeometry.width() : (board.width() + 1) * BoardGeometry.width() * 0.75f,
                  (corner & 2) == 0 ? -(board.height() + 1) * BoardGeometry.height() : BoardGeometry.height(),
                  (corner & 4) == 0 ? groundBase : top);
            if (camera.projection.val[Matrix4.M33] == 0
                  && (groundProjection.x - camera.position.x) * camera.direction.x
                        + (groundProjection.y - camera.position.y) * camera.direction.y
                        + (groundProjection.z - camera.position.z) * camera.direction.z <= camera.near) {
                // A volume crossing the near plane can cover the whole screen; the per-pixel ray still clips it.
                layerScreenBounds[0] = 0;
                layerScreenBounds[1] = 0;
                layerScreenBounds[2] = 1;
                layerScreenBounds[3] = 1;
                break;
            }
            camera.project(groundProjection, 0, 0, 1, 1);
            layerScreenBounds[0] = Math.min(layerScreenBounds[0], groundProjection.x);
            layerScreenBounds[1] = Math.min(layerScreenBounds[1], groundProjection.y);
            layerScreenBounds[2] = Math.max(layerScreenBounds[2], groundProjection.x);
            layerScreenBounds[3] = Math.max(layerScreenBounds[3], groundProjection.y);
        }
        // Include the full reduced-resolution reconstruction footprint at the volume's outer edge.
        float padX = 8f / sceneColor.getWidth(), padY = 8f / sceneColor.getHeight();
        layerScreenBounds[0] -= padX;
        layerScreenBounds[1] -= padY;
        layerScreenBounds[2] += padX;
        layerScreenBounds[3] += padY;
        shader.setUniform4fv("u_layerScreenBounds", layerScreenBounds, 0, 4);
        if (groundNoise == null) {
            // Texture construction binds its active unit. Do not replace the already-bound scene/depth sampler.
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0 + noiseUnit);
            groundNoise = noise();
        }
        groundNoise.bind(noiseUnit);
        shader.setUniformi("u_layerNoise", noiseUnit);
    }

    private void bindSand(Camera camera, BoardScene board) {
        float strength = settings.effects().sand();
        if (strength > 0) { updateGroundBase(board); }
        float height = settings.groundLayerHeight() * BoardGeometry.level();
        compositeShader.setUniformf("u_sand", strength, height, sandBase, 1 / BoardGeometry.level());
        compositeShader.setUniformf("u_groundHollow", groundHollow);
        compositeShader.setUniformf("u_sandMaxOpacity", MAX_SAND_OPACITY);
        if (strength <= 0) { return; }
        bindGroundLayer(compositeShader, camera, board, sandBase + height * 3, 4);
        compositeShader.setUniformf("u_sandWind", MathUtils.sinDeg(settings.effects().windDirection()),
              MathUtils.cosDeg(settings.effects().windDirection()), groundMotion.grains, 1 / BoardGeometry.width());
        compositeShader.setUniformf("u_sandOffset", groundMotion.sand);
        // Dust scatters the light that reaches the ground, so it does not glow on moonless maps.
        Color ground = lighting.groundLight();
        Color dust = BoardAtmosphere.SAND_DUST;
        compositeShader.setUniformf("u_sandLight", ground.r * dust.r, ground.g * dust.g, ground.b * dust.b);
    }

    /**
     * Capture premultiplied linear weather against the scene's existing opaque depth. The composite puts it over
     * fogged terrain before grading: distant terrain's fog column must not attenuate near-camera precipitation.
     * As before, weather itself has no per-particle volumetric fog integration.
     */
    void renderWeather(Camera camera, BoardScene board) {
        if (settings.effects().hasParticles()) {
            if (particles == null) {
                particles = new GpuWeatherParticles();
            }
            try {
                if (weatherLayer == null) {
                    var next = GpuHeatGlow.buffer(sceneColor.getWidth(), sceneColor.getHeight());
                    try { attachDepthTexture(next, sceneDepth); }
                    catch (RuntimeException failure) { next.dispose(); throw failure; }
                    next.getColorBufferTexture().setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
                    weatherLayer = next;
                }
                weatherLayer.begin();
                ScreenUtils.clear(0, 0, 0, 0, false);
                particles.render(camera, board, settings.effects(), sceneParticleLight(), clock);
                weatherDrawn = true;
            } finally { sceneColor.begin(); }
        }
    }

    private void renderFog(Camera camera, GpuTerrain terrain, BoardScene board) {
        updateGroundBase(board);
        float base = groundBase;
        float height = settings.groundLayerHeight() * BoardGeometry.level();
        fog.begin();
        screenState();
        fogShader.bind();
        sceneDepth.bind(0);
        fogShader.setUniformi("u_depth", 0);
        Texture water = terrain.waterDepth();
        (water == null ? sceneDepth : water).bind(4);
        fogShader.setUniformi("u_waterDepth", 4);
        float variation = options.fogHeightVariation() * BoardGeometry.level();
        float top = base + Math.max((height + variation) * 3, settings.haze() > 0 ? height * 6 : 0);
        bindGroundLayer(fogShader, camera, board,
              Math.max(top, hasRays() ? clouds.base() : base), 3);
        fogShader.setUniformf("u_fog", settings.fog() * 0.7f / BoardGeometry.level(), height, base);
        fogShader.setUniformf("u_haze", settings.haze() * 0.035f / BoardGeometry.width());
        fogShader.setUniformf("u_fogVariation", variation, options.fogDensityVariation(), BoardGeometry.level() * 0.25f);
        fogShader.setUniformf("u_noiseScale", 0.5f / BoardGeometry.width());
        fogShader.setUniformf("u_fogOffset", groundMotion.fog);
        fogShader.setUniformf("u_fogColor", lighting.fog().r, lighting.fog().g, lighting.fog().b);
        fogShader.setUniformf("u_maxOpacity", BoardAtmosphere.MAX_FOG_OPACITY);
        fogShader.setUniformf("u_rays", hasRays()
              ? BoardAtmosphere.clouds(settings).scattering() * options.rays() / BoardGeometry.width() : 0);
        fogShader.setUniformf("u_sunColor", lighting.direct().r, lighting.direct().g, lighting.direct().b);
        fogShader.setUniformf("u_sunDirection", -lighting.direction().x, -lighting.direction().y, -lighting.direction().z);
        fogShader.setUniformf("u_cloudEnabled", 0);
        fogShader.setUniformi("u_cloudShadow", 0);
        // A comparison sampler must never alias the ordinary depth sampler, even while rays are disabled.
        fogShader.setUniformi("u_geometryShadow", 2);
        fogShader.setUniformf("u_hasGeometryShadow", 0);
        fogShader.setUniformf("u_shadowStrength", lighting.shadowStrength());
        if (hasRays()) {
            clouds.bindShadow(fogShader, 1);
            var geometryShadow = terrain.environment().shadowMap;
            fogShader.setUniformf("u_hasGeometryShadow", geometryShadow == null ? 0 : 1);
            if (geometryShadow != null) {
                geometryShadow.getDepthMap().texture.bind(2);
                fogShader.setUniformMatrix("u_geometryProjection", geometryShadow.getProjViewTrans());
            }
        }
        quad.render(fogShader, GL20.GL_TRIANGLES);
        fog.end();
    }

    static void screenState() {
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        Gdx.gl.glDepthMask(false);
    }

    private void disposeBuffers() {
        if (weatherLayer != null) { weatherLayer.dispose(); weatherLayer = null; }
        weatherDrawn = false;
        if (sceneColor != null) {
            sceneColor.dispose();
            sceneColor = null;
        }
        if (sceneDepth != null) {
            sceneDepth.dispose();
            sceneDepth = null;
        }
        if (fog != null) {
            fog.dispose();
            fog = null;
        }
        if (heatGlow != null) { heatGlow.disposeBuffers(); }
    }

    /**
     * Links the heat program now (GpuBattleView's warm-up), not at the first lava or hot unit shown, and the depth
     * cues' program while they are on. FSR and each weather kind link at first use: a render resolution below 1, or
     * that weather (the board's own falls in the warm-up's frames); switched on later, the depth cues too.
     */
    void prepare() {
        if (heatGlow == null) { heatGlow = new GpuHeatGlow(quad); }
        if (depthCueStrength > 0 && depthCues == null) { depthCues = new GpuDepthCues(quad); }
    }

    @Override
    public void dispose() {
        resetContrast();
        GpuShaderManager.dispose(contrastShader);
        disposeBuffers();
        if (smaa != null) { smaa.dispose(); }
        if (fsr != null) { fsr.dispose(); }
        if (particles != null) {
            particles.dispose();
        }
        if (clouds != null) { clouds.dispose(); }
        if (heatGlow != null) { heatGlow.dispose(); }
        if (depthCues != null) { depthCues.dispose(); }
        if (groundNoise != null) { groundNoise.dispose(); }
        quad.dispose();
        GpuShaderManager.dispose(fogShader);
        GpuShaderManager.dispose(compositeShader);
    }
}
