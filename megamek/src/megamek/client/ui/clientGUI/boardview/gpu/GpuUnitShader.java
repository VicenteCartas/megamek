/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuShaderSource.replaceOnce;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.RenderContext;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.TimeUtils;

/** Unit paint, damage and imported surface lighting. The ModelBatch owns the provider and its shaders. */
final class GpuUnitShader extends DefaultShader {
    private static final String MAIN = "void main() {";
    /**
     * Where a vertex of a material textured along the board rests, and its part's way there (its UVs, turned with it):
     * the model vertex stage's; GpuTreeInstances places both by the instance.
     */
    static final String BOARD_REST = "vec2 boardRest = pos.xy;";
    static final String BOARD_WAY = "vec2 boardWay = (u_worldTrans * vec4(a_texCoord0, 0.0, 0.0)).xy;";

    private final int rotation = register("u_camoRotation");
    private final int camoEnabled = register("u_camoEnabled");
    private final int camoImageSize = register("u_camoImageSize");
    private final int paintTransform = register("u_paintTransform");
    private final int paintNormalMatrix = register("u_paintNormalMatrix");
    private final int markerTexture = register("u_markerTexture");
    private final int markerEnabled = register("u_markerEnabled");
    private final int paintPlate = register("u_paintPlate");
    private final int paintPlateEnabled = register("u_paintPlateEnabled");
    private final int damageTexture = register("u_damageTexture");
    private final int damageEnabled = register("u_damageEnabled");
    private final int damageOpacity = register("u_damageOpacity");
    private final int damageTransform = register("u_damageTransform");
    private final int opacity = register("u_unitOpacity");
    private final int materialize = register("u_materialize");
    /** The renderable being drawn shows translucent ({@link Shown#opacity}): it blends over the scene. */
    private boolean translucent;
    private final boolean displayOutput;
    /** The features of the environment lighting this program was built for (GpuCloudShadow.prefix). */
    private final int environmentFlags;
    /** Only ruby materials compile its optics (GpuCloudShadow.prefix); DefaultShader.canRender cannot tell them apart. */
    private final boolean ruby;

    private GpuUnitShader(Renderable renderable, Config config, boolean displayOutput, boolean encodeOutput) {
        super(renderable, config, GpuGlsl.compile("GPU unit material", GpuCloudShadow.prefix(renderable, config)
                    + "#define sceneUnlitFlag\n" + (encodeOutput ? "#define displayOutputFlag\n" : ""),
              config.vertexShader, displayOutput ? config.fragmentShader : GpuSceneRadiance.fragment(config.fragmentShader)));
        this.displayOutput = displayOutput;
        environmentFlags = GpuEnvironmentLighting.flags(renderable.environment);
        ruby = GpuCrystalTransmission.ruby(renderable.material);
        registerMaterialColors(this, true);
        GpuCloudShadow.register(this);
        GpuEnvironmentLighting.register(this);
        if (renderable.environment != null) {
            var environment = renderable.environment.get(GpuEnvironmentLighting.class, GpuEnvironmentLighting.TYPE);
            // Upload before render-time setters bind any material textures.
            if (environment != null) { environment.texture(); }
        }
        GpuLavaLighting.register(this, null);
        GpuAmbientOcclusion.register(this);
        GpuModelMaterial.register(this);
        GpuModelLight.register(this, () -> 0);
        register("u_normalMaps", new BaseShader.GlobalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable part, Attributes attributes) { target.set(id, 1f); }
        });
        // The shared light model's view (rain-surface.glsl viewDirection), as the terrain's programs set it.
        register("u_viewDirection", new BaseShader.GlobalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable part, Attributes attributes) {
                target.set(id, target.camera.direction);
            }
        });
        register("u_viewPosition", new BaseShader.GlobalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable part, Attributes attributes) {
                target.set(id, target.camera.position);
            }
        });
        register("u_perspective", new BaseShader.GlobalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable part, Attributes attributes) {
                target.set(id, target.camera.projection.val[Matrix4.M33] == 0 ? 1f : 0f);
            }
        });
    }

    /** DefaultShader.canRender compares the environment's mask, which leaves out its lighting's features. */
    @Override
    public boolean canRender(Renderable renderable) {
        return super.canRender(renderable) && GpuEnvironmentLighting.flags(renderable.environment) == environmentFlags
              && GpuCrystalTransmission.ruby(renderable.material) == ruby;
    }

    static DefaultShaderProvider provider() {
        return provider(false, false);
    }

    /** Model ghosts drawn over the finished scene share linear material evaluation, then encode for that target. */
    static DefaultShaderProvider displayProvider() {
        return provider(true, true);
    }

    /** Linear display light for a scoped sRGB target; neither scene storage scale nor a second encode. */
    static DefaultShaderProvider linearDisplayProvider() {
        return provider(true, false);
    }

    /**
     * libGDX's unlit model stages as they are (model.vert, unlit.frag), compiled like every board program: the
     * display-space tactical pages, fire control and route markers draw no environment.
     */
    static DefaultShaderProvider unlitProvider() {
        return new DefaultShaderProvider(GpuShaderSource.read("model.vert"), GpuShaderSource.read("unlit.frag")) {
            @Override
            protected Shader createShader(Renderable renderable) {
                return new DefaultShader(renderable, config, GpuGlsl.compile("GPU unlit model",
                      createPrefix(renderable, config), config.vertexShader, config.fragmentShader));
            }
        };
    }

    /**
     * Links the unlit program a material draws with on a vertex layout, behind the loading stage (GpuBattleView's
     * warm-up), so the first route, range or outline links none. The program depends only on which attributes are set.
     */
    static void prepareUnlit(ModelBatch batch, Material material, VertexAttributes layout) {
        Renderable sample = new Renderable();
        sample.material = material;
        Mesh mesh = new Mesh(true, 0, 0, layout);
        sample.meshPart.set("unlit-warmup", mesh, 0, 0, GL20.GL_TRIANGLES);
        try { batch.getShaderProvider().getShader(sample); }
        finally { mesh.dispose(); }
    }

    private static DefaultShaderProvider provider(boolean displayOutput, boolean encodeOutput) {
        return new DefaultShaderProvider(GpuCloudShadow.vertex(vertexSource(linearVertex(GpuShaderSource.read("model.vert")))),
              GpuCloudShadow.fragment(fragmentSource(fragment()))) {
            @Override
            protected Shader createShader(Renderable renderable) {
                return new GpuUnitShader(renderable, config, displayOutput, encodeOutput);
            }
        };
    }

    // Insertion points in model.vert and model.frag: replaceOnce fails explicitly if an edit moves one.
    static String vertexSource(String source) {
        String declarations = GpuShaderSource.read("unit-material.vert");
        source = replaceOnce(source, MAIN, declarations + "\n" + MAIN + "\n    unitMaterialCoordinates();\n", "vertex");
        return replaceOnce(source, "v_diffuseUV = u_diffuseUVTransform.xy + a_texCoord0 * u_diffuseUVTransform.zw;",
              "v_diffuseUV = unitDiffuseUV();", "vertex");
    }

    static String fragmentSource(String source) {
        String declarations = GpuShaderSource.read("unit-material.frag");
        source = replaceOnce(source, MAIN, declarations + "\n" + MAIN, "fragment");
        source = replaceOnce(source, "// unit-overlays", "diffuse.rgb = unitOverlays(diffuse.rgb);", "fragment");
        // One uniform rather than a blended program variant: a unit fades without a program compiled after loading.
        return replaceOnce(source, "    // Tactical draws after the scene composite", "    fragColor.a *= u_unitOpacity;\n"
              + "    fragColor.rgb = unitMaterialize(fragColor.rgb);\n"
              + "    // Tactical draws after the scene composite", "fragment");
    }

    /**
     * The model vertex stage (model.vert) with linear colours: units, props, buildings and liquids share it with the
     * terrain. Its own per-vertex lighting is unused; the fragment stages light every surface (model.frag,
     * shadeSurface).
     */
    static String linearVertex(String source) {
        source = replaceOnce(source, "#if defined(diffuseTextureFlag) || defined(specularTextureFlag) || defined(emissiveTextureFlag)",
              "#if defined(diffuseTextureFlag) || defined(specularTextureFlag) || defined(emissiveTextureFlag) || defined(modelSurfaceFlag)",
              "vertex");
        // A material textured along the board (GpuModelMaterial#boardUV) maps its colour, emission and surface maps from
        // where it rests on the board, one repeat per |u_modelBoardUV| units: north up, or with a negative repeat along
        // the way its part carries, one sense for both directions, north up where it carries none (MeepleShatter#boardUV
        // bakes the same), so turned and neighbouring pieces' floors and roofs join. The depth pass keeps the authored
        // UVs (only an alpha-tested material would show it).
        source = replaceOnce(source, MAIN,
              "#ifdef modelSurfaceFlag\nout vec2 v_modelUV;\nuniform float u_modelBoardUV;\n"
                    + "vec2 boardUV(vec2 rest, vec2 way, float repeat) {\n"
                    + "    if (repeat > 0.0 || dot(way, way) < 1e-8) { return vec2(rest.x, -rest.y) / abs(repeat); }\n"
                    + "    way = normalize(way);\n"
                    + "    if (way.y < -1e-4 || abs(way.y) <= 1e-4 && way.x < 0.0) { way = -way; }\n"
                    + "    return vec2(dot(rest, way), rest.x * way.y - rest.y * way.x) / -repeat;\n"
                    + "}\n#endif\n" + MAIN
                    + "\n#ifdef modelSurfaceFlag\nv_modelUV = a_texCoord0;\n#endif\n", "vertex");
        String placed = "gl_Position = u_projViewTrans * pos;";
        source = replaceOnce(source, placed, placed + "\n#ifdef modelSurfaceFlag\nif (u_modelBoardUV != 0.0) {\n"
              + "    " + BOARD_REST + "\n    " + BOARD_WAY + "\n"
              + "    v_modelUV = boardUV(boardRest, boardWay, u_modelBoardUV);\n"
              + "#ifdef diffuseTextureFlag\n    v_diffuseUV = u_diffuseUVTransform.xy + v_modelUV * u_diffuseUVTransform.zw;\n#endif\n"
              + "#ifdef emissiveTextureFlag\n    v_emissiveUV = u_emissiveUVTransform.xy + v_modelUV * u_emissiveUVTransform.zw;\n#endif\n"
              + "}\n#endif\n", "vertex");
        // The vertex colours' sRGB transfer (toLinear) alone: the light model belongs to the fragment stages, which
        // also define its environment lighting. Mesa refuses to link a vertex stage that declares it without them.
        source = replaceOnce(source, MAIN, GpuShaderSource.read("color-space.glsl") + "\n" + MAIN, "vertex");
        // A recolourable model's shared mesh takes its placement's colours before any lighting (RigidGlb.recolour).
        source = replaceOnce(source, MAIN, GpuShaderSource.read("model-colour-slots.glsl") + "\n" + MAIN, "vertex");
        String color = "v_color = a_color;";
        return replaceOnce(source, color, color
              + "\n#ifdef colourSlotsFlag\nv_color.rgb = slotColour(a_color.rgb, a_colourSlot, u_slotColours);\n"
              + "v_slotGlow = v_color.rgb / max(a_color.rgb, vec3(1e-4));\n#endif\n"
              + "#if (defined(lightingFlag) || defined(sceneUnlitFlag) || defined(linearTextureFlag))"
              + " && !defined(linearColorsFlag) && !defined(vertexDataFlag)\n"
              + "v_color.rgb = toLinear(v_color.rgb);\n#endif\n", "vertex");
    }

    /**
     * Imported models' fragment stage (model.frag): linear albedo lit by the board's one light model, plus emission.
     * Factors arrive from the material setters in the input domain used by this pass. Scene output stays linear.
     * The unit provider also converts unlit scene materials; terrain's final tactical overlays
     * retain display output because they do not set sceneUnlitFlag.
     */
    static String fragment() {
        return GpuShaderSource.modelLighting(GpuShaderSource.read("model.frag"));
    }

    /** Replace libGDX's factor setters before init; never mutate shared authored colors or vertex color metadata. */
    static void registerMaterialColors(DefaultShader shader, boolean linearInputs) {
        registerMaterialColor(shader, Inputs.diffuseColor, ColorAttribute.Diffuse, linearInputs);
        registerMaterialColor(shader, Inputs.emissiveColor, ColorAttribute.Emissive, linearInputs);
        registerMaterialColor(shader, Inputs.specularColor, ColorAttribute.Specular, linearInputs);
    }

    private static void registerMaterialColor(DefaultShader shader, BaseShader.Uniform input, long type,
          boolean linearInputs) {
        shader.register(input, new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable part, Attributes attributes) {
                var color = attributes.get(ColorAttribute.class, type).color;
                boolean decode = linearInputs && !part.material.has(GpuLinearColors.TYPE);
                target.set(id, decode ? GpuColor.linear(color.r) : color.r,
                      decode ? GpuColor.linear(color.g) : color.g,
                      decode ? GpuColor.linear(color.b) : color.b, color.a);
            }
        });
    }

    @Override
    protected void bindMaterial(Attributes attributes) {
        super.bindMaterial(attributes);
        if (!displayOutput) { bindSceneBlending(context, attributes); }
        if (translucent) {
            // What fades hides nothing behind it, neither another fading unit nor the smoke drawn after the units.
            context.setBlending(true, GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
            context.setDepthMask(false);
        }
    }

    /**
     * A unit instance's userData: its outline colour and its own hex's visibility (the alpha, GpuUnitVisibility), and how
     * much of it shows. Below 1 it draws translucent, after the other units ({@code GpuBattleView.renderUnits}).
     */
    static final class Shown extends com.badlogic.gdx.graphics.Color {
        /** How long (ms) a just-deployed unit takes to build up from its feet. */
        static final long MATERIALIZE_MILLIS = 750;

        float opacity = 1;
        /**
         * The deployment's materialize: when (TimeUtils#millis) the unit began to build up from its feet between its
         * lowest and highest board heights {@link #bottom} and {@link #top}, 0 when whole. The shader eases it by the
         * clock, so an instance a frame no longer updates still ends whole.
         */
        long materializeStart;
        /**
         * The same build up set by the frame, 1 when whole: a destroyed unit an action builds up and down
         * (UnitPlayback#built). The shader shows the lesser of the two.
         */
        float built = 1;
        float bottom;
        float top;

        /** How far a unit that began to build up at {@code start} has, easing out: 1 when whole or never started. */
        static float materialized(long start) {
            if (start == 0) {
                return 1;
            }
            return eased(Math.min(1, TimeUtils.timeSinceMillis(start) / (float) MATERIALIZE_MILLIS));
        }

        /** The build up's ease out, from its share of time {@code t} (0 to 1). */
        static float eased(float t) {
            return 1 - (1 - t) * (1 - t);
        }
    }

    /** Scene alpha is coverage for uncovered sky and transparent exports, not another color channel. */
    static void bindSceneBlending(RenderContext context, Attributes attributes) {
        var blend = attributes.get(BlendingAttribute.class, BlendingAttribute.Type);
        if (blend != null && blend.blended && blend.sourceFunction == GL20.GL_SRC_ALPHA
              && blend.destFunction == GL20.GL_ONE_MINUS_SRC_ALPHA) {
            context.setBlending(true, blend.sourceFunction, blend.destFunction, GL20.GL_ONE, blend.destFunction);
        }
    }

    @Override
    public void render(Renderable part, Attributes attributes) {
        var paint = attributes.get(GpuUnitCamouflage.Paint.class, GpuUnitCamouflage.Paint.TYPE);
        var damage = attributes.get(UnitDamageDisplay.Overlay.class, UnitDamageDisplay.Overlay.TYPE);
        var diffuse = attributes.get(TextureAttribute.class, TextureAttribute.Diffuse);
        // A sampler the draw does not read still holds a texture on its unit (GpuModelMaterial#spare).
        var spare = GpuModelMaterial.spare(attributes);
        int spareUnit = spare == null ? -1 : context.textureBinder.bind(spare);
        set(damageEnabled, damage == null ? 0f
              : part.material.id.endsWith(UnitDamageDisplay.WRECKED_SUFFIX) ? 2f : 1f);
        if (damage != null) {
            set(damageOpacity, damage.opacity);
            set(damageTexture, context.textureBinder.bind(damage.texture));
            set(damageTransform, damage.cos, damage.sin, damage.offsetU, damage.offsetV);
        } else if (spareUnit >= 0) {
            set(damageTexture, spareUnit);
        }
        set(rotation, paint == null ? 1 : paint.cos, paint == null ? 0 : paint.sin);
        boolean camo = paint != null && diffuse != null;
        set(camoEnabled, camo ? 1f : 0f);
        if (paint != null) {
            set(paintTransform, paint.transform);
            set(paintNormalMatrix, paint.normalMatrix);
        }
        if (camo) {
            var texture = diffuse.textureDescription.texture;
            set(camoImageSize, (float) texture.getWidth(), (float) texture.getHeight());
        }
        boolean marker = paint != null && paint.marker != null;
        set(markerEnabled, marker ? 1f : 0f);
        if (marker) {
            set(markerTexture, context.textureBinder.bind(paint.marker));
        } else if (spareUnit >= 0) {
            set(markerTexture, spareUnit);
        }
        boolean plate = paint != null && paint.plate != null;
        set(paintPlateEnabled, plate ? 1f : 0f);
        if (plate) {
            set(paintPlate, context.textureBinder.bind(paint.plate));
        } else if (spareUnit >= 0) {
            set(paintPlate, spareUnit);
        }
        float shown = part.userData instanceof Shown unit ? unit.opacity : 1;
        set(opacity, shown);
        float built = part.userData instanceof Shown unit
              ? Math.min(Shown.materialized(unit.materializeStart), unit.built) : 1;
        if (built < 1 && part.userData instanceof Shown unit) {
            set(materialize, built, unit.bottom, unit.top, Math.max(1e-3f, (unit.top - unit.bottom) / 28));
        } else {
            set(materialize, 1f, 0f, 1f, 1f);
        }
        translucent = shown < 1;
        super.render(part, attributes);
    }
}
