/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GLTexture;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.RenderableProvider;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DirectionalLightsAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.g3d.shaders.DepthShader;
import com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.g3d.utils.RenderContext;
import com.badlogic.gdx.graphics.g3d.utils.RenderableSorter;
import com.badlogic.gdx.graphics.g3d.utils.ShaderProvider;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import megamek.client.ui.clientGUI.boardview.gpu.GpuTerrainLayer.MeshBatch;
import megamek.common.board.Coords;
import megamek.logging.MMLogger;

/** Chunked solid hex columns, flat decals and authored features; owns all GL resources it creates. */
final class GpuTerrain implements Disposable {
    private static final MMLogger LOGGER = MMLogger.create(GpuTerrain.class);
    static final int CHUNK_SIZE = TerrainLod.CHUNK_SIZE;
    /** A sculpt vertex's kind (terrain-sculpt.frag, dry ground below .125) on a natural bridge's muddy deck top. */
    static final float DECK_MUD_KIND = .1f;
    /** Muddy rock shell; its occlusion byte carries depth below the local top edge for the shader's draining fade. */
    static final float DECK_MUD_RIM_KIND = 140 / 255f;
    // Extra texels pay for the camera guard band without reducing the former 2048-map's world-space detail.
    static final float DEFAULT_BUILDING_OPACITY = 0.5f;
    private final GpuAssets assets;
    /** Whether {@link #assets} belong to another terrain ({@link #GpuTerrain(GpuTerrain)}). */
    private final boolean borrowedAssets;
    private final BoardSurface.Cache pickingSurfaces = new BoardSurface.Cache();
    private final GpuRoads.Masks roadMaskData = new GpuRoads.Masks();
    private final boolean liquidShaderAnimation;
    private final boolean proceduralWater;
    private final Texture rainNoise = rainNoise();
    private final Texture waterDetail = GpuWaterShader.detailTexture();
    /** Wind waves for every open water surface, advanced once per frame. */
    private final GpuOcean ocean = new GpuOcean();
    private final GpuWaterExposure waterExposure = new GpuWaterExposure();
    /** The nearest wave of this frame; the scene's depth keeps the beds and units beneath it. */
    private final GpuWaterDepth waterDepth = new GpuWaterDepth();
    /** The shared FFT engine with a viscous lava spectrum, independent of wind. */
    private final GpuOcean lavaOcean = new GpuOcean(true);
    /** Units standing partly in open water, which the water laps against. */
    private final GpuWaders waders = new GpuWaders();
    private final GpuUnitModels unitModels;
    /**
     * A Classic hex's art: its column art with its rims in its base slot, which a water neighbour's bank borrows, and
     * a natural bridge's cap art in its rims slot. Enhanced packs none.
     */
    private record GroundSlot(Coords coords, boolean rims) { }
    /**
     * Enhanced paint (kind 0); Classic tileset decals (1) and object sprites (2), which only a Classic build packs.
     */
    private record DecalSlot(Coords coords, int kind) { }
    private final GpuTextures<GroundSlot> ground = new GpuTextures<>(true, true);
    private final BoardRim rims = new BoardRim();
    private final GpuTextures<DecalSlot> decals = new GpuTextures<>(false, true);
    /**
     * Family programs and the generic program each sorts with (familyGroup); others sort alone. A program's entry lives
     * from its init, once its provider keeps it, until its dispose: a shader edit disposes the programs it replaced only
     * after their replacements are installed, and a rejected edit disposes the replacements it prepared.
     */
    private final Map<Shader, Shader> shaderGroups = new IdentityHashMap<>();
    private final GpuTerrainBatch terrainBatch = new GpuTerrainBatch(material -> material.has(Ground.TYPE)
          && !material.has(GpuLiquidShader.Frame.TYPE) && !material.has(GpuWaterShader.TYPE), this::shaderGroup);
    private final GpuTerrainPages terrainPages = new GpuTerrainPages(terrainBatch::eligible);
    /** The ground material cache's tops (GpuMaterialCache) in shared draws of their own, which switch programs. */
    private final GpuTerrainPages topPages = new GpuTerrainPages(terrainBatch::eligible);
    private final GpuMaterialCache materialCache = new GpuMaterialCache();
    /** The cache's fill programs (TerrainShaders with fill) and their batch, made with the first fill program. */
    private ModelBatch fillBatch;
    /** A fill's draws: copies of the tops in their generic materials (GpuMaterialCache.fillMaterial). */
    private final GpuRenderableCopies fillCopies = new GpuRenderableCopies();
    /** Whether the installed chunks hold cache tops the cache draws (cachesGround): their programs link in the warm-up. */
    private boolean cacheTops;
    /** Authored paint of nearby chunks in shared draws: each chunk numbers its paint draws in painter order. */
    private final GpuTerrainPages paintPages = new GpuTerrainPages(part -> true);
    private final GpuWaterPages waterPages = new GpuWaterPages();
    private final GatheringBatch batch = new GatheringBatch(GpuShaderManager.provider("Terrain",
          () -> new TerrainShaders(false)), terrainBatch);
    /**
     * Ground cover's own batch, flushed between the depth pre-pass and the colour batch's flush (renderCover): grass in
     * the colour batch would end its terrain grouping (GpuTerrainBatch). It shares the colour batch's programs and
     * render context, and owns no GL resource: it is never disposed, which would dispose the shared programs.
     */
    private final ModelBatch coverBatch = new ModelBatch(batch.getRenderContext(), batch.getShaderProvider(),
          new GpuOpaqueSorter());
    /**
     * The depth twins of the terrain's programs (depthPrepass), from the same shader ladder; renderPrepass draws them.
     * Created with the first twin, so a terrain without the pre-pass composes and registers no second ladder.
     */
    private ShaderProvider prepassShaders;

    /**
     * The terrain's shader ladder. With {@code depthOnly} it builds each program's depth twin instead: the same prefix,
     * vertex stage and setters, and a fragment stage that writes depth alone, after a cutout's alpha test, which it repeats
     * exactly (terrain-foliage.frag's depthPrepassFlag). Every vertex stage declares gl_Position invariant, and a cutout's
     * also the inputs of its alpha test, so a program and its twin cover the same pixels at the same depth.
     */
    private final class TerrainShaders extends DefaultShaderProvider {
        private final boolean depthOnly;
        /** The ground material cache's fill programs (GpuMaterialCache): the sculpt evaluator alone, into its pages. */
        private final boolean fill;
        /**
         * A solid surface twin's fragment stage, read while the provider is built: the shader editor tracks the files a
         * provider reads then, so an edit of it rebuilds the twins.
         */
        private final String depthFragment;

        // A scar patch draws with its receiver's program (GpuSurfaceScars): every vertex stage forwards the patch's
        // page UV, and the sculpt and model fragment stages draw the patch.
        TerrainShaders(boolean depthOnly) { this(depthOnly, false); }

        TerrainShaders(boolean depthOnly, boolean fill) {
            super(GpuSurfaceScars.vertex(invariantPosition(GpuCloudShadow.vertex(GpuUnitShader.linearVertex(
                        GpuShaderSource.read("model.vert"))))),
                  GpuSurfaceScars.fragment(GpuCloudShadow.fragment(GpuUnitShader.fragment())));
            this.depthOnly = depthOnly;
            this.fill = fill;
            depthFragment = depthOnly ? GpuShaderSource.read("terrain-depth.frag") : null;
        }

        private final DefaultShader.Config groundShader = new DefaultShader.Config(config.vertexShader,
              litFragment("terrain-normal.frag"));
        private final DefaultShader.Config pitShader = new DefaultShader.Config(config.vertexShader,
              GpuShaderSource.read("terrain-pit.frag"));
        private final DefaultShader.Config backdropShader = new DefaultShader.Config(config.vertexShader,
              GpuShaderSource.read("terrain-backdrop.frag"));
        private final DefaultShader.Config roadShader = new DefaultShader.Config(GpuRoads.vertex(config.vertexShader),
              litFragment("terrain-road.frag"));
        private final DefaultShader.Config sculptShader = new DefaultShader.Config(
              GpuMaterialCache.vertex(GpuSurfaceBlend.vertex(config.vertexShader)), litFragment("terrain-sculpt.frag"));
        private final DefaultShader.Config foliageShader = new DefaultShader.Config(coverageVertex(config.vertexShader),
              litFragment("terrain-foliage.frag"));
        private final DefaultShader.Config instancedFoliageShader = new DefaultShader.Config(
              GpuTreeInstances.vertex(foliageShader.vertexShader), foliageShader.fragmentShader);
        private final DefaultShader.Config instancedPropShader = new DefaultShader.Config(
              GpuTreeInstances.vertex(config.vertexShader), config.fragmentShader);
        private final DefaultShader.Config vegetationShader = new DefaultShader.Config(GpuGroundCover.vertex(config.vertexShader),
              GpuCloudShadow.fragment(GpuShaderSource.terrainLighting(GpuShaderSource.read("terrain-vegetation.frag"))));
        private final DefaultShader.Config biomeVegetationShader = new DefaultShader.Config(
              GpuBiomeVegetation.vertex(config.vertexShader), vegetationShader.fragmentShader);
        private final DefaultShader.Config waterShader = new DefaultShader.Config(GpuWaterWaves.vertex(config.vertexShader),
              litFragment("water-surface.frag"));
        private final DefaultShader.Config waterDepthShader = new DefaultShader.Config(waterShader.vertexShader,
              GpuShaderSource.read("water-depth.frag"));
        private final DefaultShader.Config waterfallShader = new DefaultShader.Config(config.vertexShader,
              litFragment("water-fall.frag"));
        private final DefaultShader.Config sprayShader = new DefaultShader.Config(GpuWaterfall.vertex(config.vertexShader),
              litFragment("water-spray.frag"));
        // The board-edge section's top follows the waves it meets (water-waves.vert).
        private final DefaultShader.Config waterCutShader = new DefaultShader.Config(waterShader.vertexShader,
              litFragment("water-cut.frag"));
        private final DefaultShader.Config waterBeyondShader = new DefaultShader.Config(waterShader.vertexShader,
              GpuShaderSource.read("water-beyond.frag"));
        private final DefaultShader.Config liquidShader = new DefaultShader.Config(config.vertexShader,
              GpuLiquidShader.fragment(config.fragmentShader));
        private final DefaultShader.Config waterLiquidShader = new DefaultShader.Config(waterShader.vertexShader,
              GpuLiquidShader.fragment(waterShader.fragmentShader));
        private final DefaultShader.Config waterfallLiquidShader = new DefaultShader.Config(config.vertexShader,
              GpuLiquidShader.fragment(waterfallShader.fragmentShader));
        private final DefaultShader.Config solidMagmaShader = new DefaultShader.Config(config.vertexShader,
              litFragment("terrain-magma-solid.frag"));
        private final DefaultShader.Config flowingMagmaShader = new DefaultShader.Config(config.vertexShader,
              litFragment("terrain-magma-flow.frag"));

        @Override
        protected Shader createShader(Renderable renderable) {
            boolean linearTexture = linearDiffuse(renderable.material);
            int ordinaryFamily = GpuSurfaceBlend.ordinaryFamily(renderable.material);
            var waterMode = waterMode(renderable.material);
            var magma = renderable.material.get(GpuMagmaShader.class, GpuMagmaShader.TYPE);
            boolean flowingMagma = flowingMagma(renderable.material);
            boolean animated = renderable.material.has(GpuLiquidShader.Frame.TYPE);
            // The plain and foliage shaders, the only ones with instanced variants, first: instancedShader decides them.
            DefaultShader.Config chosen = instancedShader(renderable.material)
                  ? renderable.material.has(Foliage.TYPE)
                        ? GpuTreeInstances.instanced(renderable) ? instancedFoliageShader : foliageShader
                        : GpuTreeInstances.instanced(renderable) ? instancedPropShader : config
                  : renderable.material.has(Pit.TYPE) ? pitShader
                  : renderable.material.has(GpuBackdrop.TYPE) ? backdropShader
                  : magma != null && !renderable.material.has(Sculpt.TYPE)
                        ? flowingMagma ? flowingMagmaShader : solidMagmaShader
                  : renderable.material.has(GpuBiomeVegetation.Kind.TYPE) ? biomeVegetationShader
                  : renderable.material.has(GpuGroundCover.Wind.TYPE) ? vegetationShader
                  : renderable.material.has(Sculpt.TYPE) ? sculptShader
                  : renderable.material.has(Ground.TYPE)
                        ? renderable.material.has(GpuRoads.TYPE) || renderable.material.has(BridgeDeck.TYPE)
                              ? roadShader : groundShader
                  : waterMode != null ? switch (waterMode) {
                      case SURFACE -> animated ? waterLiquidShader : waterShader;
                      case DEPTH -> waterDepthShader;
                      case FALL -> animated ? waterfallLiquidShader : waterfallShader;
                      case SPRAY -> sprayShader;
                      case CUT -> waterCutShader;
                      case BEYOND -> waterBeyondShader;
                  } : animated ? liquidShader : config;
            // Only these programs use model.frag (GpuUnitShader.fragment); custom surfaces own their factor colours.
            boolean modelColors = chosen == config || chosen == instancedPropShader || chosen == liquidShader;
            String prefix = GpuCloudShadow.prefix(renderable, chosen)
                  + (renderable.environment != null ? "#define sceneUnlitFlag\n" : "")
                  + (renderable.material.has(Sculpt.TYPE) || waterMode != null
                        || renderable.material.has(GpuGroundCover.Wind.TYPE)
                        || renderable.material.has(GpuBiomeVegetation.Kind.TYPE)
                        || renderable.material.has(Impostor.TYPE)
                        || renderable.material.has(GpuRoads.Coats.TYPE) ? "#define vertexDataFlag\n" : "")
                  + "#define LUNAR_FAMILY " + BoardScene.Surface.LUNAR.ordinal() + ".0\n"
                  + "#define FUNGUS_FAMILY " + BoardScene.Surface.FUNGUS.ordinal() + ".0\n"
                  + "#define DESERT_FAMILY " + BoardScene.Surface.DESERT.ordinal() + ".0\n"
                  + "#define MARS_FAMILY " + BoardScene.Surface.MARS.ordinal() + ".0\n"
                  + "#define VOLCANO_FAMILY " + BoardScene.Surface.VOLCANO.ordinal() + ".0\n"
                  + "#define TROPICAL_FAMILY " + BoardScene.Surface.TROPICAL.ordinal() + ".0\n"
                  + "#define VOLCANIC_CRUST_FAMILY " + BoardSurfaceBlend.CRUST + ".0\n"
                  + "#define VOLCANIC_BANK_FAMILY " + BoardSurfaceBlend.BANK + ".0\n"
                  + "#define FOOT_METRES " + BoardSurfaceBlend.FOOT_METRES + "\n"
                  + "#define CLOSE_PIXEL " + GpuMaterialCache.CLOSE_PIXEL + "\n"
                  + (SUBLEVEL_FLOORS ? "#define sublevelFloorFlag\n#define FLOOR_FAMILIES " + FLOORS.length + "\n" : "")
                  + GpuIceShader.prefix(renderable.material)
                  + (renderable.material.has(GpuBiomeVegetation.Kind.TYPE) ? "#define biomeVegetationFlag\n" : "")
                  + (renderable.material.has(GpuSurfaceBlend.TYPE) ? "#define terrainBlendFlag\n" : "")
                  + (ordinaryFamily != -1 ? "#define VOLCANIC_ORDINARY_FAMILY " + ordinaryFamily + "\n" : "")
                  + (renderable.material.has(GpuMagmaShader.TYPE) ? "#define volcanicFlag\n" : "")
                  + (renderable.material.has(GpuRoads.TYPE) ? "#define roadFlag\n" : "")
                  + (renderable.material.has(GpuRoads.Mask.TYPE) ? "#define roadMaskFlag\n" : "")
                  + (renderable.material.has(GpuRoads.Coats.TYPE) ? "#define roadCoatFlag\n" : "")
                  + (renderable.material.has(GpuRoads.Soil.TYPE) ? "#define roadSoilFlag\n" : "")
                  + (renderable.material.has(BridgeDeck.TYPE) ? "#define bridgeDeckFlag\n" : "")
                  + (renderable.material.has(GpuRoads.Maps.TYPE) ? "#define roadMapsFlag\n" : "")
                  + (renderable.material.has(Impostor.TYPE) && GpuTreeInstances.instanced(renderable)
                        ? "#define impostorFlag\n" : "")
                  // Only sculpted ground has the biome stage; the marker still keeps the environments' programs apart. A
                  // fill draws without an environment and always has it: a field's ground is in its pages, and on a board
                  // without biomes the stage finds no cover.
                  + (!renderable.material.has(Sculpt.TYPE) ? "" : pruning && !fill ? GpuBiomeSurface.prefix(renderable)
                        : "#define biomeMaskFlag\n");
            String fragment = renderable.material.has(GpuBuildingCutaway.TYPE)
                  ? GpuBuildingCutaway.fragment(chosen.fragmentShader, "v_cloudPosition.z") : chosen.fragmentShader;
            if (renderable.material.has(GpuTilesetTerrain.Grading.TYPE)) {
                fragment = GpuTilesetTerrain.fragment(fragment);
            }
            // Unlit tactical pages share this provider but draw after scene composition in display units.
            if (renderable.environment != null && waterMode != GpuWaterShader.Mode.DEPTH) {
                fragment = GpuSceneRadiance.fragment(fragment);
            }
            String shaderName = "GPU terrain / " + (renderable.material.has(Sculpt.TYPE) ? "sculpt"
                  : renderable.material.has(Pit.TYPE) ? "pit"
                  : renderable.material.has(GpuBackdrop.TYPE) ? "backdrop"
                  : magma != null ? flowingMagma ? "magma-flow" : "magma-solid"
                  : waterMode != null ? "water-" + waterMode : renderable.material.has(Ground.TYPE) ? "ground" : "model")
                  + (renderable.material.has(GpuSurfaceBlend.TYPE) ? " blend" : "")
                  + (ordinaryFamily != -1 ? " ordinary-" + ordinaryFamily : "")
                  + (renderable.material.has(GpuMagmaShader.TYPE) ? " volcanic" : "")
                  + (GpuIceShader.mode(renderable.material) != 0 ? " ice-" + GpuIceShader.mode(renderable.material) : "")
                  + (GpuEnvironmentLighting.cloudShadow(renderable.environment) != null ? " cloud" : "")
                  + (renderable.environment != null && renderable.environment.shadowMap != null ? " shadow" : "")
                  + (GpuTreeInstances.instanced(renderable) ? " instanced" : "")
                  + GpuFamilyPrograms.name(renderable.material, Sculpt.TYPE);
            // From the colour program's stages: the prefix is also the vertex stage's, which a twin shares.
            prefix += GpuShaderSource.finishPrefix(FINISHES.size(), SCULPT_LAYERS.size(), chosen.vertexShader, fragment);
            // The ground material cache (GpuMaterialCache): a fill program, or a cache top's cached-lit twin.
            boolean cached = !depthOnly && !fill && GpuMaterialCache.isCached(renderable.material);
            if (fill) {
                prefix += "#define materialFillFlag\n";
                shaderName += " cache-fill";
            } else if (cached) {
                prefix += "#define materialCachedFlag\n";
                shaderName += " cached";
            }
            // A charted wall's (GpuMaterialCache.Charts) fill and cached-lit programs read its chart; its direct draws
            // share the other ground's programs, which leave the chart unread.
            boolean charted = (fill || cached)
                  && GpuMaterialCache.charted(renderable.meshPart.mesh.getVertexAttributes());
            if (charted) {
                prefix += "#define wallChartFlag\n";
                shaderName += " wall";
            }
            if (depthOnly) {
                // A cutout's twin returns after its alpha test (GpuGlsl.source moves the version line above the define);
                // a solid surface's twin has an empty fragment stage.
                fragment = renderable.material.has(FloatAttribute.AlphaTest) ? "#define depthPrepassFlag\n" + fragment
                      : depthFragment;
                shaderName += " depth twin";
            }
            // Sculpted relief draws tessellated where the context offers it (GpuTessellation), and its twin alike.
            boolean tessellated = tessellates(renderable);
            if (tessellated) {
                shaderName += " tessellated";
                prefix += "#define tessellationFlag\n";
                if (GpuTessellation.hasContacts(renderable.meshPart.mesh.getVertexAttributes())) {
                    prefix += "#define terrainContactFlag\n";
                }
            }
            // The pre-pass draws twin by twin (renderPrepass): a twin sorts with no program.
            Shader group = depthOnly || fill ? null : familyGroup(renderable, shaders);
            DefaultShader result = new DefaultShader(renderable, chosen,
                  GpuGlsl.compile(shaderName, prefix + GpuFamilyPrograms.defines(renderable.material, Sculpt.TYPE),
                        tessellated ? GpuTessellation.vertex(chosen.vertexShader) : chosen.vertexShader, fragment)) {
                private final float[] families = GpuFamilyPrograms.constants(renderable.material, Sculpt.TYPE);
                private final int baseAliases = families == null ? 0 : GpuFamilyPrograms.baseAliases(renderable.material);
                private final int normalMapsUniform = register("u_normalMaps");
                private final int terrainParallaxUniform = register("u_terrainParallax");
                private final int iceNormalsUniform = register("u_iceNormals");
                private final int magmaTimeUniform = register("u_magmaTime");
                private final int magmaOceanUniform = register("u_magmaOcean");
                private final int magmaOceanScaleUniform = register("u_magmaOceanScale");
                private final int roadProfileUniform = register("u_roadProfile");
                private final int wetnessUniform = register("u_wetness");
                private final int viewDirectionUniform = register("u_viewDirection");
                private final int viewPositionUniform = register("u_viewPosition");
                private final int perspectiveUniform = register("u_perspective");
                private final int rainNoiseUniform = register("u_rainNoise");
                private final int groundDamageUniform = register("u_groundDamage");
                private final int groundDamageSizeUniform = register("u_groundDamageSize");
                private final int groundDamageGridUniform = register("u_groundDamageGrid");
                private final int groundScorchUniform = register("u_groundScorch");
                private final int rainScaleUniform = register("u_rainScale");
                private final int rainTimeUniform = register("u_rainTime");
                private final int rippleDetailUniform = register("u_rippleDetail");
                private final int rainDetailUniform = register("u_rainDetail");
                private final int skyUniform = register("u_rainSky");
                private final int horizonUniform = register("u_rainHorizon");
                private final int waterEffectsUniform = register("u_waterEffects");
                private final int gravityUniform = register("u_gravity");
                private final int windUniform = register("u_wind");
                private final int waterWindUniform = register("u_waterWind");
                private final int waterDriftUniform = register("u_waterDrift");
                private final int waterStormUniform = register("u_waterStorm");
                private final int vegetationPhaseUniform = register("u_vegetationPhase");
                private final int metreUniform = register("u_worldMetre");
                private final int coverPixelsUniform = register("u_coverPixels");
                private final int coverHexWidthUniform = register("u_coverHexWidth");
                private final int gridShadeUniform = register("u_gridShade");
                private final int waterDetailUniform = register("u_waterDetail");
                private final int[] waterOceanUniforms = { register("u_waterOcean0"), register("u_waterOcean1"),
                      register("u_waterOcean2") };
                private final int waterShapeUniform = register("u_waterShape");
                private final int wavePixelsUniform = register("u_wavePixels");
                private final int waveFadeUniform = register("u_waveFade");
                private final int waterExposureUniform = register("u_waterExposure");
                private final int waterNearestUniform = register("u_waterNearest");
                private final int waterExposureMapUniform = register("u_waterExposureMap");
                private final int waterOceanScaleUniform = register("u_waterOceanScale");
                private final int waderCountUniform = register("u_waderCount");
                private final int wadersUniform = register("u_waders");
                private final int waderMotionUniform = register("u_waderMotion");
                private final int levelUniform = register("u_levelHeight");
                private final int sculptMetreUniform = register("u_metre");
                private final int waterLineUniform = register("u_waterLine");
                private final int biomeHexesUniform = register("u_biomeHexes");
                private final int biomeBoardUniform = register("u_biomeBoard");
                private final int iceBoardUniform = register("u_iceBoard");
                private final int biomeSoilUniform = register("u_biomeSoilLayer");
                private final int meadowFinishUniform = register("u_meadowFinish");
                private final int biomeSoilTileUniform = register("u_biomeSoilTile");
                private final int mudMaterialUniform = register("u_mudMaterial");
                private final int bankMaterialsUniform = register("u_bankMaterials");
                private final int flatRockUniform = register("u_flatRock");
                private final int surfaceLayersUniform = register("u_surfaceLayers");
                // Upload before render-time texture-unit assignment, like the other shared maps.
                private final GpuAssets.FinishArrays finishArrays = program.hasUniform("u_finishSurface[0]")
                      ? assets.finishArrays(FINISHES, SCULPT_LAYERS.size()) : null;
                private int surfaceLayersUnit = -1;
                private final int tundraLayersUniform = register("u_tundraLayers");
                private final int tundraTilesUniform = register("u_tundraTiles");
                private final int tundraCarpetsUniform = register("u_tundraCarpets");
                private final int tundraCarpetDataUniform = register("u_tundraCarpetData");
                // The tundra carpets' own full-size arrays, uploaded with the program like the finish arrays.
                private final GpuAssets.MaterialArrays tundraCarpets = program.hasUniform("u_tundraCarpets")
                      ? assets.tundraCarpets() : null;
                private int tundraCarpetsUnit = -1, tundraCarpetDataUnit = -1;
                private final int treePitMaterialUniform = register("u_treePitMaterial");
                private final int scorchedMaterialUniform = register("u_scorchedMaterial");
                private final Vector3 detailPosition = new Vector3();
                private int rainNoiseUnit = -1, waterDetailUnit = -1, biomeUnit = -1;
                private int groundDamageUnit = -1;
                private final int[] waterOceanUnits = { -1, -1, -1 };
                private int magmaOceanUnit = -1;
                private int waterShapeUnit = -1;
                private int waterExposureUnit = -1;
                private int waterNearestUnit = -1;
                private long globalsPass = -1;
                private final long instances = instanceMask(renderable);
                private final int iceMode = GpuIceShader.mode(renderable.material);
                private final int environmentFlags = GpuEnvironmentLighting.flags(renderable.environment);
                private final VertexAttributes createdLayout = renderable.meshPart.mesh.getVertexAttributes();
                private final long vertexLayout = GpuSurfaceScars.receiverLayout(createdLayout);
                private final int textureCoordinates = createdLayout.getTextureCoordinates();

                @Override
                public boolean canRender(Renderable other) {
                    // libGDX binds only the instance attributes of the renderable a shader was created for (instanceMask).
                    // Family programs (GpuFamilyPrograms) first: the provider's scan rejects them cheaply.
                    return GpuFamilyPrograms.draws(families, baseAliases, other.material, Sculpt.TYPE)
                          && GpuMaterialCache.isCached(other.material) == cached
                          && (!fill && !cached
                                || GpuMaterialCache.charted(other.meshPart.mesh.getVertexAttributes()) == charted)
                          && sameMaterialVariant(other.material, iceMode, linearTexture, ordinaryFamily, waterMode,
                                flowingMagma)
                          // A shadow map changes the compiled samplers without changing the environment's mask.
                          && shadowMap == (other.environment != null && other.environment.shadowMap != null)
                          && tessellates(other) == tessellated
                          && instances == instanceMask(other) && drawsLayout(other);
                }

                /**
                 * DefaultShader.canRender without a scar patch's marking and page UV: a receiver's program draws its
                 * patches (GpuSurfaceScars), and a program first made for a patch draws its receiver. The environment's
                 * mask leaves out its lighting's features (GpuEnvironmentLighting.flags): they are compared here.
                 */
                private boolean drawsLayout(Renderable other) {
                    VertexAttributes vertices = other.meshPart.mesh.getVertexAttributes();
                    long mask = (other.environment == null ? 0 : other.environment.getMask()) | other.material.getMask()
                          | IntAttribute.CullFace | DepthTestAttribute.Type;
                    return (other.bones == null || other.bones.length <= config.numBones
                          && vertices.getBoneWeights() <= config.numBoneWeights)
                          && vertices.getTextureCoordinates() == textureCoordinates
                          && ((mask ^ attributesMask) & ~GpuSurfaceScars.Marking.TYPE) == 0
                          && GpuSurfaceScars.receiverLayout(vertices) == vertexLayout
                          && (other.environment != null) == lighting
                          && GpuEnvironmentLighting.flags(other.environment) == environmentFlags;
                }

                @Override
                public void init(ShaderProgram program, Renderable created) {
                    // The attributes a program binds come from the mesh it was created for: add its patches' page UV.
                    Mesh layout = GpuSurfaceScars.layout(program, created.meshPart.mesh);
                    if (layout == null) {
                        super.init(program, created);
                        return;
                    }
                    Renderable mapped = new Renderable().set(created);
                    mapped.meshPart.mesh = layout;
                    try { super.init(program, mapped); } finally { layout.dispose(); }
                }

                @Override
                public void begin(Camera camera, RenderContext context) {
                    super.begin(camera, context);
                    // Blended road layers repeatedly switch programs. Their camera, weather and light inputs
                    // stay constant for this render call; uniforms remain resident when another program binds.
                    if (globalsPass == shadingPass) { return; }
                    globalsPass = shadingPass;
                    set(normalMapsUniform, normalMaps ? 1f : 0f);
                    set(terrainParallaxUniform, terrainParallax ? 1f : 0f);
                    set(iceNormalsUniform, normalMaps ? 1f : 0f);
                    set(magmaTimeUniform, magmaClock);
                    set(magmaOceanScaleUniform, lavaOcean.texture() == null ? 0f : GpuOcean.lavaScale());
                    set(roadProfileUniform, BoardRoad.JOIN_REACH, BoardRoad.WHEEL_OFFSET,
                          BoardRoad.TRACK_HALF_WIDTH, GpuRoads.WHEEL_TINT);
                    set(wetnessUniform, wetness);
                    set(viewDirectionUniform, camera.direction);
                    set(viewPositionUniform, camera.position);
                    set(perspectiveUniform, camera.projection.val[Matrix4.M33] == 0 ? 1f : 0f);
                    set(waterEffectsUniform, waterEffects && waterVisible() ? 1f : 0f);
                    set(gravityUniform, gravity);
                    set(windUniform, wind);
                    // Water follows the ocean's eased wind, so its ripples, gusts and foam turn with the waves.
                    Vector3 waterWind = ocean.wind();
                    set(waterWindUniform, waterWind == null ? wind : waterWind);
                    set(waterDriftUniform, ocean.drift());
                    set(waterStormUniform, ocean.gain());
                    set(vegetationPhaseUniform, vegetationPhase);
                    set(metreUniform, BoardRelief.detailMetres(1));
                    set(coverHexWidthUniform, BoardGeometry.width());
                    // Scene pixels: the render resolution's target, not the window's.
                    set(coverPixelsUniform, BoardGeometry.width()
                          * Math.abs(camera.projection.val[Matrix4.M11]) * camera.viewportHeight * .5f
                          * Gdx.graphics.getBackBufferHeight() * BoardCamera.renderScale / Math.max(1f, Gdx.graphics.getHeight()));
                    set(gridShadeUniform, BoardGeometry.tuning().gridShade());
                    set(levelUniform, BoardGeometry.level());
                    // The Tactical View shows no craters, ground damage or scorch (setFluffHidden). Authored scorch also
                    // shows where combat scars do not (the editor, its previews).
                    boolean damaged = combatScars && !fluffHidden && groundDamage.marks() > 0
                          && groundDamage.texture() != null;
                    set(groundDamageSizeUniform, damaged ? groundDamage.width() : 0, damaged ? groundDamage.height() : 0);
                    set(groundDamageGridUniform, groundDamage.tileWorldSize(), (float) groundDamage.tileColumns(), (float) groundDamage.tileRows());
                    boolean scorched = !fluffHidden && groundDamage.scorchShown() && groundDamage.texture() != null;
                    set(groundScorchUniform, scorched ? 1f : 0f, scorched ? groundDamage.scorchHeat() : 0f);
                    set(sculptMetreUniform, BoardRelief.metres(1));
                    set(waterLineUniform, BoardGeometry.hexScale());
                    set(waterExposureMapUniform, waterExposure.mapping[0], waterExposure.mapping[1],
                          waterExposure.mapping[2], waterExposure.mapping[3]);
                    set(wavePixelsUniform, BoardGeometry.width() * Math.abs(camera.projection.val[Matrix4.M11])
                          * Gdx.graphics.getBackBufferHeight() * BoardCamera.renderScale * .5f);
                    float waveStart = Math.max(8, TerrainLod.tuning().threshold(2) * 1.1f);
                    set(waveFadeUniform, waveStart, Math.max(50, waveStart + 1));
                    if (has(biomeHexesUniform)) {
                        set(biomeBoardUniform, (float) biomes.width(), (float) biomes.height());
                        set(iceBoardUniform, (float) biomes.iceWidth(), (float) biomes.iceHeight());
                    }
                    if (has(biomeSoilUniform)) {
                        set(biomeSoilUniform, 2f * SCULPT_LAYERS.indexOf("biome-soil"));
                        set(biomeSoilTileUniform, assets.sculptTile("biome-soil") * .45f);
                    }
                    if (has(meadowFinishUniform)) {
                        float tile = assets.sculptTile("grassland-ground");
                        set(meadowFinishUniform, SCULPT_LAYERS.indexOf("grassland-ground"), tile,
                              tile * BoardRelief.metres(1));
                    }
                    if (has(mudMaterialUniform)) {
                        set(mudMaterialUniform, 2f * SCULPT_LAYERS.indexOf("biome-mud"),
                              assets.sculptTile("biome-mud"));
                    }
                    if (has(bankMaterialsUniform)) {
                        set(bankMaterialsUniform, 2f * SCULPT_LAYERS.indexOf("temperate-bank"),
                              assets.sculptTile("temperate-bank"),
                              2f * SCULPT_LAYERS.indexOf("dirt-debris"), assets.sculptTile("dirt-debris"));
                    }
                    if (has(flatRockUniform)) {
                        set(flatRockUniform, 2f * SCULPT_LAYERS.indexOf("temperate-cliff"),
                              2f * SCULPT_LAYERS.indexOf("rock-ground"),
                              assets.sculptTile("rock-ground"));
                    }
                    if (finishArrays != null) {
                        float[] surface = finishArrays.surface(), tint = finishArrays.tint(), glow = finishArrays.glow();
                        program.setUniform4fv("u_finishSurface[0]", surface, 0, surface.length);
                        if (program.hasUniform("u_finishEmission[0]")) {
                            program.setUniform4fv("u_finishEmission[0]", tint, 0, tint.length);
                        }
                        if (program.hasUniform("u_finishGlow[0]")) { program.setUniform3fv("u_finishGlow[0]", glow, 0, glow.length); }
                        float[] relief = finishArrays.relief();
                        if (relief.length > 0 && program.hasUniform("u_finishRelief[0]")) {
                            program.setUniform2fv("u_finishRelief[0]", relief, 0, relief.length);
                        }
                    }
                    if (SUBLEVEL_FLOORS && program.hasUniform("u_floorMaterials[0]")) {
                        float[] floors = new float[3 * FLOORS.length];
                        for (int family = 0; family < FLOORS.length; family++) {
                            Floor floor = FLOORS[family];
                            floors[3 * family] = floor == null ? -1 : 2f * SCULPT_LAYERS.indexOf(floor.material());
                            floors[3 * family + 1] = floor == null ? 0 : assets.sculptTile(floor.material());
                            floors[3 * family + 2] = floor != null && floor.edges() ? 1 : 0;
                        }
                        program.setUniform3fv("u_floorMaterials[0]", floors, 0, floors.length);
                    }
                    if (program.hasUniform("u_roadRelief[0]")) {
                        float[] relief = assets.roadRelief();
                        program.setUniform4fv("u_roadRelief[0]", relief, 0, relief.length);
                    }
                    if (has(tundraLayersUniform)) {
                        set(tundraLayersUniform, (float) SCULPT_LAYERS.indexOf("tundra-ground") * 2,
                              (float) SCULPT_LAYERS.indexOf("tundra-mat") * 2,
                              (float) SCULPT_LAYERS.indexOf("tundra-crust") * 2,
                              (float) SCULPT_LAYERS.indexOf("tundra-ground-variant") * 2);
                        set(tundraTilesUniform, assets.sculptTile("tundra-ground"), assets.sculptTile("tundra-mat"),
                              assets.sculptTile("tundra-crust"), assets.sculptTile("tundra-ground-variant"));
                    }
                    if (has(treePitMaterialUniform)) {
                        set(treePitMaterialUniform, 2f * SCULPT_LAYERS.indexOf("concrete-mulch"),
                              assets.sculptTile("concrete-mulch"));
                    }
                    if (has(scorchedMaterialUniform)) {
                        set(scorchedMaterialUniform, 2f * SCULPT_LAYERS.indexOf("ground-scorch"),
                              assets.sculptTile("ground-scorch"));
                    }
                    // BaseShader skips uniforms absent from a program. All lit surfaces share the same rain clock.
                    set(rainScaleUniform, 1f / BoardGeometry.width());
                    set(rainTimeUniform, clock);
                    Color sky = atmosphere == null ? GpuColor.linear(Color.GRAY) : atmosphere.sky();
                    Color horizon = atmosphere == null ? GpuColor.linear(Color.LIGHT_GRAY) : atmosphere.horizon();
                    set(skyUniform, sky.r, sky.g, sky.b);
                    set(horizonUniform, horizon.r, horizon.g, horizon.b);
                    if (has(waterOceanScaleUniform)) {
                        // Without the wave simulation the water keeps its static ripples alone.
                        if (ocean.texture() == null) { set(waterOceanScaleUniform, 0f, 0f, 0f); }
                        else { set(waterOceanScaleUniform, GpuOcean.scale(0), GpuOcean.scale(1), GpuOcean.scale(2)); }
                        set(waderCountUniform, waders.count);
                        if (waders.count > 0 && has(wadersUniform) && has(waderMotionUniform)) {
                            program.setUniform4fv(loc(wadersUniform), waders.bodies, 0, waders.count * 4);
                            program.setUniform4fv(loc(waderMotionUniform), waders.motion, 0, waders.count * 4);
                        }
                    }
                    // Orthographic projected size is shared by every part in this pass.
                    if (camera.projection.val[Matrix4.M33] != 0) { setDetailUniforms(); }
                }

                @Override
                protected void bindMaterial(Attributes attributes) {
                    super.bindMaterial(attributes);
                    GpuUnitShader.bindSceneBlending(context, attributes);
                    // A scar patch (opaque, like its receiver) covers the receiver where its image does: elsewhere it
                    // ends with alpha 0 (scar-material.glsl), which this blending leaves unseen. It writes no depth.
                    if (attributes.has(GpuSurfaceScars.Marking.TYPE)) {
                        context.setBlending(true, GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, GL20.GL_ONE,
                              GL20.GL_ONE_MINUS_SRC_ALPHA);
                        context.setDepthMask(false);
                    }
                    // A draw the depth pre-pass drew keeps that depth: it shades only where its own depth won, writing none.
                    if (prepassed && twin(this, attributes) != null) {
                        context.setDepthTest(prepassDepthFunction);
                        context.setDepthMask(false);
                    }
                }

                @Override
                public void render(Renderable part, Attributes attributes) {
                    // Chunk/material textures can evict these shared textures from the binder's LRU slots.
                    // Touch them for each draw so their sampler uniforms never point at another chunk's field.
                    // BaseShader.set skips uniforms absent from this shader; resident textures are reused.
                    if (finishArrays != null) {
                        surfaceLayersUnit = bindShared(surfaceLayersUniform, finishArrays.maps(), surfaceLayersUnit);
                    }
                    rainNoiseUnit = bindShared(rainNoiseUniform, rainNoise, rainNoiseUnit);
                    if (tundraCarpets != null) {
                        tundraCarpetsUnit = bindShared(tundraCarpetsUniform, tundraCarpets.color(), tundraCarpetsUnit);
                        tundraCarpetDataUnit = bindShared(tundraCarpetDataUniform, tundraCarpets.data(), tundraCarpetDataUnit);
                    }
                    if (has(groundDamageUniform)) {
                        groundDamageUnit = bindShared(groundDamageUniform, groundDamage.binding(), groundDamageUnit);
                    }
                    waterDetailUnit = bindShared(waterDetailUniform, waterDetail, waterDetailUnit);
                    if (has(biomeHexesUniform)) { biomeUnit = bindShared(biomeHexesUniform, biomes.texture(), biomeUnit); }
                    for (int cascade = 0; cascade < waterOceanUniforms.length; cascade++) {
                        Texture waves = ocean.waves(cascade);
                        waterOceanUnits[cascade] = bindShared(waterOceanUniforms[cascade],
                              waves == null ? waterDetail : waves, waterOceanUnits[cascade]);
                    }
                    if (has(waterShapeUniform)) {
                        Texture shape = ocean.displacement();
                        waterShapeUnit = bindShared(waterShapeUniform, shape == null ? waterDetail : shape, waterShapeUnit);
                    }
                    if (has(waterExposureUniform)) {
                        waterExposureUnit = bindShared(waterExposureUniform, waterExposure.texture(), waterExposureUnit);
                    }
                    if (has(waterNearestUniform) && waterDepth.texture() != null) {
                        waterNearestUnit = bindShared(waterNearestUniform, waterDepth.texture(), waterNearestUnit);
                    }
                    if (has(magmaOceanUniform)) {
                        Texture waves = lavaOcean.texture();
                        magmaOceanUnit = bindShared(magmaOceanUniform, waves == null ? waterDetail : waves, magmaOceanUnit);
                    }
                    if (camera.projection.val[Matrix4.M33] == 0) {
                        detailPosition.set(part.meshPart.center).mul(part.worldTransform);
                        setDetailUniforms();
                    }
                    if (tessellated) { GpuTessellation.render(part, () -> super.render(part, attributes)); }
                    else { super.render(part, attributes); }
                }

                @Override
                public void init() {
                    super.init();
                    if (group != null) { shaderGroups.put(this, group); }
                }

                @Override
                public void dispose() {
                    shaderGroups.remove(this);
                    // A replaced colour program (a key) or twin (a value) leaves its pairs: their draws draw as before
                    // until the warm-up pairs the replacements, behind the loading stage (prepareTerrainMaterials).
                    if (prepassTwins.remove(this) != null | prepassTwins.values().removeIf(twin -> twin == this)) {
                        terrainMaterialsReady = false;
                    }
                    super.dispose();
                }

                private int bindShared(int uniform, GLTexture texture, int previousUnit) {
                    if (!has(uniform)) { return previousUnit; }
                    int unit = context.textureBinder.bind(texture);
                    // The binder must see every use even when resident; only the redundant sampler upload is skipped.
                    if (unit != previousUnit) { set(uniform, unit); }
                    return unit;
                }

                private void setDetailUniforms() {
                    ShadingDetail detail = detailAt(camera, detailPosition);
                    set(rippleDetailUniform, detail.ripple());
                    set(rainDetailUniform, detail.rain());
                }
            };
            if (modelColors) {
                GpuUnitShader.registerMaterialColors(result, renderable.environment != null || linearTexture);
            }
            GpuCloudShadow.register(result);
            GpuBackdrop.register(result);
            GpuEnvironmentLighting.register(result);
            if (renderable.environment != null) {
                var lighting = renderable.environment.get(GpuEnvironmentLighting.class, GpuEnvironmentLighting.TYPE);
                // Texture uploads must finish before local setters begin assigning the draw's sampler bindings.
                if (lighting != null) { lighting.texture(); }
            }
            GpuLavaLighting.register(result);
            GpuAmbientOcclusion.register(result);
            if (tessellated) {
                GpuTessellation.tessellated(result.program);
                GpuTessellation.register(result);
            }
            GpuModelMaterial.register(result);
            GpuModelLight.register(result, () -> clock);
            GpuBuildingCutaway.register(result);
            GpuSurfaceScars.register(result);
            if (fill) { materialCache.registerFill(result); }
            if (cached) { materialCache.registerCached(result, charted); }
            GpuLiquidShader.register(result);
            GpuMagmaShader.register(result, rainNoise);
            // Upload before drawing: creating ice maps from a uniform setter disturbs the active texture bindings.
            if (renderable.material.has(GpuIceShader.TYPE)) { assets.ice(); }
            GpuIceShader.register(result, assets);
            GpuWaterShader.register(result);
            for (boolean glow : new boolean[] { false, true }) {
                result.register(glow ? "u_biomeGlow" : "u_biomeFinish", new BaseShader.LocalSetter() {
                    @Override
                    public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                        var kind = attributes.get(GpuBiomeVegetation.Kind.class, GpuBiomeVegetation.Kind.TYPE);
                        if (kind == null) { return; }
                        float[] values = glow ? kind.glow : kind.surface;
                        target.set(id, values[0], values[1], values[2]);
                    }
                });
            }
            result.register("u_biomeLod", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    var kind = attributes.get(GpuBiomeVegetation.Kind.class, GpuBiomeVegetation.Kind.TYPE);
                    if (kind != null) { target.set(id, kind.value); }
                }
            });
            result.register("u_groundResponse", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    Ground ground = attributes.get(Ground.class, Ground.TYPE);
                    if (ground != null) { target.set(id, ground.value); }
                }
            });
            result.register("u_roadFinish", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    GpuRoads.Maps maps = attributes.get(GpuRoads.Maps.class, GpuRoads.Maps.TYPE);
                    if (maps != null) { target.set(id, (float) maps.material); }
                }
            });
            result.register("u_roadSurface", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    GpuRoads.Maps maps = attributes.get(GpuRoads.Maps.class, GpuRoads.Maps.TYPE);
                    if (maps != null) { target.set(id, maps.textureDescription); }
                }
            });
            for (boolean roads : new boolean[] { true, false }) {
                for (boolean color : new boolean[] { true, false }) {
                    String uniform = (roads ? "u_road" : "u_sculpt") + (color ? "Colors" : "Data");
                    result.register(uniform, new BaseShader.LocalSetter() {
                        @Override
                        public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                            GpuRoads.Coats coats = attributes.get(GpuRoads.Coats.class, GpuRoads.Coats.TYPE);
                            if (coats != null) {
                                var maps = roads ? coats.roads : coats.sculpt;
                                target.set(id, color ? maps.color() : maps.data());
                            }
                        }
                    });
                }
            }
            result.register("u_roadMask", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    var mask = attributes.get(TextureAttribute.class, GpuRoads.Mask.TYPE);
                    if (mask != null) { target.set(id, mask.textureDescription); }
                }
            });
            result.register("u_bridgeDeckCoverage", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    FloatAttribute deck = attributes.get(FloatAttribute.class, BridgeDeck.TYPE);
                    target.set(id, deck == null ? 1 : deck.value);
                }
            });
            result.register("u_roadTransition", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    FloatAttribute road = attributes.get(FloatAttribute.class, GpuRoads.TYPE);
                    target.set(id, road == null ? 0 : road.value);
                }
            });
            result.register("u_sculptLayers", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    SculptLayers maps = attributes.get(SculptLayers.class, SculptLayers.TYPE);
                    if (maps != null) { target.set(id, maps.layers[0], maps.layers[1], maps.layers[2], maps.layers[3]); }
                }
            });
            result.register("u_foliage", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    Foliage foliage = attributes.get(Foliage.class, Foliage.TYPE);
                    if (foliage != null) { target.set(id, foliage.value); }
                }
            });
            result.register("u_foliageProxy", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    String role = renderable.material.id;
                    target.set(id, "canopy-cutout".equals(role) || "canopy-snow-cutout".equals(role) ? 1f : 0f);
                }
            });
            result.register("u_impostorLift", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    Impostor impostor = attributes.get(Impostor.class, Impostor.TYPE);
                    if (impostor != null) { target.set(id, impostor.value); }
                }
            });
            result.register("u_sculptTiles", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    SculptTiles tiles = attributes.get(SculptTiles.class, SculptTiles.TYPE);
                    if (tiles != null) { target.set(id, tiles.metres[0], tiles.metres[1], tiles.metres[2], tiles.metres[3]); }
                }
            });
            result.register("u_sculptFamily", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    Sculpt sculpt = attributes.get(Sculpt.class, Sculpt.TYPE);
                    if (sculpt != null) { target.set(id, sculpt.value); }
                }
            });
            result.register("u_coverFamilies", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    GpuSurfaceBlend blend = attributes.get(GpuSurfaceBlend.class, GpuSurfaceBlend.TYPE);
                    if (blend != null) {
                        target.set(id, blend.families[0], blend.families[1], blend.families[2], blend.families[3]);
                    }
                }
            });
            result.register("u_coverResponses", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    GpuSurfaceBlend blend = attributes.get(GpuSurfaceBlend.class, GpuSurfaceBlend.TYPE);
                    if (blend != null) { target.set(id, blend.responses[0], blend.responses[1], blend.responses[2], blend.responses[3]); }
                }
            });
            for (boolean color : new boolean[] { true, false }) {
                result.register(color ? "u_terrainLayers" : "u_terrainData", new BaseShader.LocalSetter() {
                    @Override
                    public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                        SculptLayers layers = attributes.get(SculptLayers.class, SculptLayers.TYPE);
                        if (layers != null) { target.set(id, color ? layers.texture.color() : layers.texture.data()); }
                    }
                });
            }
            for (int family = 0; family < 4; family++) {
                int index = family;
                for (boolean layers : new boolean[] { false, true }) {
                    result.register((layers ? "u_coverLayers" : "u_coverTiles") + family, new BaseShader.LocalSetter() {
                        @Override
                        public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                            GpuSurfaceBlend blend = attributes.get(GpuSurfaceBlend.class, GpuSurfaceBlend.TYPE);
                            if (blend != null) {
                                float[] values = (layers ? blend.layers : blend.tiles)[index];
                                target.set(id, values[0], values[1], values[2], values[3]);
                            }
                        }
                    });
                }
            }
            return result;
        }
    }

    /** The colour batch; the depth pre-pass draws the renderables it gathered before they flush (renderPrepass). */
    private static final class GatheringBatch extends ModelBatch {
        GatheringBatch(ShaderProvider shaders, RenderableSorter sorter) { super(shaders, sorter); }

        /** This flush's renderables in submission order, until end() flushes them. */
        Array<Renderable> gathered() { return renderables; }
    }

    private final ModelBatch depthBatch = new ModelBatch(GpuShaderManager.provider("Shadows", () ->
          GpuTreeInstances.depthProvider(new DepthShader.Config(null, GpuShaderSource.replaceOnce(
                GpuShaderSource.read("shadow-depth.frag"), "// SHADOW_MAP_PASS", "#define SHADOW_PASS\n//",
                "shadow depth")))),
          new GpuOpaqueSorter());
    /** The sun's shadow map and its caches; the terrain's depth pass and the units cast into it. */
    private final GpuTerrainShadows shadowMap = new GpuTerrainShadows(new GpuTerrainShadows.Casters() {
        @Override
        public void terrain(Camera light, List<ModelInstance> units) { renderDepth(light, units, depthBatch, true, true); }

        @Override
        public void units(Camera light, List<ModelInstance> units) {
            depthBatch.begin(light);
            try { renderDepthUnits(light, units, depthBatch); }
            finally { depthBatch.end(); }
        }

        @Override
        public BoundingBox bounds(ModelInstance unit) { return unitBounds(unit); }
    });

    void shadersChanged() { refreshShadows(); }

    /** What selects a sculpt material's generic program besides its families; the family marker is left out. */
    private static Object familyVariant(Material material) {
        return List.of(material.getMask() & ~GpuFamilyPrograms.Constants.TYPE, GpuIceShader.mode(material),
              linearDiffuse(material), GpuSurfaceBlend.ordinaryFamily(material), GpuTessellation.curved(material));
    }

    /**
     * Draw order decides depth-tie winners. A family program sorts as the generic program that draws its material
     * unmarked, so swapping it in changes no pixel (GpuOpaqueSorter): that program's group, from the provider's programs.
     * Null for a generic program.
     */
    private Shader familyGroup(Renderable renderable, Iterable<Shader> programs) {
        boolean cached = GpuMaterialCache.isCached(renderable.material);
        if (!renderable.material.has(GpuFamilyPrograms.Constants.TYPE) && !cached) { return null; }
        Renderable generic = new Renderable().set(renderable);
        generic.material = new Material(renderable.material);
        generic.material.remove(GpuFamilyPrograms.Constants.TYPE);
        // A cached-lit program (GpuMaterialCache) sorts, and takes part in the pre-pass, as its top's generic program.
        if (cached) { generic.material.id = ""; }
        for (Shader program : programs) {
            if (program.canRender(generic)) { return shaderGroup(program); }
        }
        return null;
    }

    /** The shader whose draws a shader's draws sort among: its generic program for a family program. */
    private Shader shaderGroup(Shader shader) {
        Shader program = GpuShaderProvider.unwrap(shader);
        return shaderGroups.getOrDefault(program, program);
    }

    /**
     * The depth twin a draw of this colour program takes part in the pre-pass with, or null: it draws as before. The
     * pre-pass and the colour pass both ask, so a draw takes part in both or in neither.
     */
    private Shader twin(Shader colour, Attributes material) {
        Prepass category = prepass(material);
        return prepasses(category) && (category != Prepass.OBJECTS || !objectsFar)
              ? prepassTwins.get(shaderGroup(colour)) : null;
    }

    /**
     * Whether the depth pre-pass draws a category: those of depthPrepass, and all relief while ambient occlusion reads
     * the pre-pass depth (GpuAmbientOcclusion in the environment's lighting, so its switch prepares the twins as a
     * lighting change).
     */
    private boolean prepasses(Prepass category) {
        return category != null && (depthPrepass.contains(category)
              || (category == Prepass.RELIEF || category == Prepass.CLIFFS)
                    && environmentLighting.ambientOcclusion != null);
    }

    /**
     * The pre-pass category of an opaque, depth-writing draw whose twin repeats its coverage exactly; null for any other
     * draw. Blended, decal, scar, cut-away, tileset, water, liquid, vegetation and road-mask draws, alpha-tested ground
     * and objects, and objects other than building shells stay out: a building's faded floors blend and its cut-away is
     * flagged, so it joins only while whole.
     */
    private static Prepass prepass(Attributes material) {
        var blend = material.get(BlendingAttribute.class, BlendingAttribute.Type);
        if (blend != null && blend.blended || material.has(DepthTestAttribute.Type)
              || material.has(GpuSurfaceScars.Marking.TYPE) || material.has(GpuBuildingCutaway.TYPE)
              || material.has(GpuTilesetTerrain.Grading.TYPE)) { return null; }
        // terrain-foliage.frag discards by the alpha test alone, which a cutout's twin repeats. Plants are GLB models
        // too (GpuModelMaterial), so they are told apart first.
        if (material.has(Foliage.TYPE)) { return material.has(Impostor.TYPE) ? Prepass.IMPOSTORS : Prepass.FOLIAGE; }
        // model.frag discards only under an alpha test: an opaque shell's empty twin repeats its coverage. Other objects
        // select the same programs, so the flag decides each draw (GpuModelMaterial.occluder).
        var surface = material.get(GpuModelMaterial.class, GpuModelMaterial.TYPE);
        if (surface != null) {
            return surface.occluder && !material.has(FloatAttribute.AlphaTest) ? Prepass.OBJECTS : null;
        }
        // The relief's fragment stages never discard; these materials' stages do, or are not relief.
        if (blend != null || material.has(FloatAttribute.AlphaTest) || material.has(GpuWaterShader.TYPE)
              || material.has(GpuLiquidShader.Frame.TYPE) || material.has(GpuGroundCover.Wind.TYPE)
              || material.has(GpuBiomeVegetation.Kind.TYPE) || material.has(GpuRoads.Mask.TYPE)) { return null; }
        Ground ground = material.get(Ground.class, Ground.TYPE);
        if (ground != null && ground.cliffs) { return Prepass.CLIFFS; }
        return ground != null || material.has(Sculpt.TYPE) || material.has(Pit.TYPE)
              || material.has(GpuMagmaShader.TYPE) ? Prepass.RELIEF : null;
    }

    /** Relief that a cliff chunk measures and marks (Ground.cliffs): all but the pits' and bare magma's. */
    static boolean cliffRelief(Attributes material) {
        return prepass(material) == Prepass.RELIEF && material.has(Ground.TYPE);
    }

    /** Every terrain vertex stage: a depth twin and its colour program place each vertex identically. */
    private static String invariantPosition(String vertex) {
        return GpuShaderSource.replaceOnce(vertex, "void main() {", "invariant gl_Position;\nvoid main() {",
              "depth pre-pass");
    }

    /** A cutout's vertex stage: the outputs its alpha test reads are invariant too (foliage-coverage.glsl). */
    private static String coverageVertex(String vertex) {
        return GpuShaderSource.replaceOnce(vertex, "void main() {",
              GpuShaderSource.read("foliage-coverage.glsl") + "\nvoid main() {", "depth pre-pass");
    }

    /** Native checks follow the family programs a view requested. */
    GpuFamilyPrograms familyPrograms() { return familyPrograms; }

    /**
     * Redraw the shadow map at the next pass, as terrain, light and shader changes do. The camera's level changes do
     * not: the map's trees and scatter take their levels from its texel (renderDepth), and a building's or the scatter's
     * visibility change waits, with the previous silhouette, for the camera movement that changed it to refit the shadow.
     */
    void refreshShadows() { shadowMap.invalidate(); }

    record ShadingDetail(float ripple, float rain) { }

    static ShadingDetail detail(Camera camera, Renderable part) {
        return detailAt(camera, part == null ? Vector3.Zero : new Vector3(part.meshPart.center).mul(part.worldTransform));
    }

    private static ShadingDetail detailAt(Camera camera, Vector3 position) {
        float pixels = BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera, position);
        return new ShadingDetail(MathUtils.clamp((pixels * Math.abs(camera.direction.z) - 28) / 60, 0, 1),
              MathUtils.clamp((pixels - 12) / 28, 0, 1));
    }
    /** Every tree of the board, drawn instanced; each pass gathers the trees of the chunks it draws. */
    private final GpuTreeInstances trees;
    private final Environment environment = new Environment();
    private final GpuEnvironmentLighting environmentLighting = new GpuEnvironmentLighting();
    private final GpuLavaLighting lavaLighting;
    private final List<Chunk> chunks = new ArrayList<>();
    // At most four in-flight chunks (two for the camera) and eight replaced chunks. Half the cores, at most eight,
    // build terrain: each chunk spreads its hexes across the pool, while the other half keeps rendering, input and
    // garbage collection responsive. All GL ownership stays on the render thread.
    static final int DEFAULT_WORKERS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
    /** Sections built at once, so also the most task lines the loading screen shows. */
    static final int BUILD_JOBS = 4;
    /**
     * Camera refinements in flight: the next chunk prepares on the workers while this one's render-thread stages run.
     * Its hexes already spread across the pool, so a third or fourth only shared the busy workers, delayed the first
     * installs and lengthened frames.
     */
    static final int REFINE_JOBS = 2;
    private final ExecutorService detailWorker = TerrainSettings.workers(DEFAULT_WORKERS);
    private final Map<Integer, Chunk> detailCache = new LinkedHashMap<>();
    /** {@code columns}: a Classic chunk ({@link #setColumns}). */
    /** {@code stale}: a camera job a zoom has passed (wanted): its workers stop at their next check. */
    private record DetailJob(long generation, int index, Chunk source, TerrainLod lod, BoardScene scene,
          TerrainSettings settings, float floor, boolean rebuilding, boolean columns, CompletableFuture<Prepared> surfaces,
          ChunkBuild build, CompletableFuture<Void> meshes, TerrainLoadProgress progress, AtomicBoolean stale) {
        boolean ready() { return build == null ? surfaces.isDone() : meshes == null || meshes.isDone(); }
    }
    record Prepared(Map<Coords, BoardSurface> surfaces, GpuWaterShader.Field.Prepared water,
          GpuWaterShader.Field.Prepared lava,
          Map<Coords, BoardTacticalGeometry.Surface> topography, Map<Coords, BoardPlants> plants,
          Map<Coords, SculptPlan> sculpts,
          Map<Coords, BoardFlow.Current> currents, Map<Coords, List<RoadPatch>> roads,
          Map<Coords, BoardBridge.Deck> bridges, Map<Coords, BoardBridge.Shape> bridgeShapes,
          Map<Coords, Map<BoardSurface.Side, List<BoardSurface.Face>>> walls, Set<Coords> reused,
          Map<Coords, List<BoardDecals.Stamp>> paint, boolean paintOnly) { }
    record RoadPatch(GpuRoads.Patch patch, GpuRoads.MaskData mask,
          List<BoardTacticalGeometry.Triangle> triangles, boolean flat, BoardScene.Tile source) { }
    /** {@code columns}: the Classic graphics' build ({@link #setColumns}). */
    private record Request(long generation, BoardScene scene, TerrainSettings settings, boolean columns,
          boolean tessellated) { }
    private record UpdatePlan(float floor, BoardConcrete coast, Map<Coords, BoardFlow.Current> currents,
          boolean all, Set<Coords> changed, Set<Coords> changedTiles, Set<Coords> paintOnly, Map<GroundSlot, BoardScene.Pixels> colors,
          Map<DecalSlot, BoardScene.Pixels> decals,
          Map<Coords, List<BoardDecals.Stamp>> paint) { }
    private Map<Coords, List<BoardDecals.Stamp>> installedPaint = Map.of();
    private static final class Rebuild {
        final Request request;
        final CompletableFuture<UpdatePlan> preparation;
        final TerrainLoadProgress progress;
        final Set<Integer> remaining = new HashSet<>();
        final Set<Integer> started = new HashSet<>();
        final Map<Integer, Chunk> replacements = new HashMap<>();
        UpdatePlan plan;

        Rebuild(Request request, CompletableFuture<UpdatePlan> preparation, TerrainLoadProgress progress) {
            this.request = request;
            this.preparation = preparation;
            this.progress = progress;
        }
    }
    /**
     * The jobs in flight besides {@link #detailJob}, the one whose render-thread stage runs: a rebuild's sections
     * (queueChunks) or the camera's refinements (refineChunks), never both.
     */
    private final List<DetailJob> pending = new ArrayList<>();
    private DetailJob detailJob;
    private volatile long meshGeneration;
    private Request requested;
    private Rebuild rebuild;
    private TerrainSettings installedSettings;
    private EditorRoutes editorRoutes;
    /** The elevators a scene change moves this frame ({@link #present}); null while none moves. */
    private Lifts lifts;
    private final ArrayDeque<Chunk> retiredChunks = new ArrayDeque<>();
    private final Set<Coords> atlasChanges = new HashSet<>();
    private final Set<Coords> atlasTileChanges = new HashSet<>();
    private Map<GroundSlot, BoardScene.Pixels> terrainPixels = Map.of();
    private Map<DecalSlot, BoardScene.Pixels> decalPixels = Map.of();
    private final GpuGroundCover groundCover = new GpuGroundCover();
    private final GpuBiomeVegetation biomeVegetation = new GpuBiomeVegetation();
    // The scene fields() last looked at, and whether it has fields.
    private BoardScene fieldsScene;
    private boolean fields;
    private final GpuBiomeSurface biomes = new GpuBiomeSurface();
    private final GpuGroundDamage groundDamage = new GpuGroundDamage();
    private final GpuSurfaceScars surfaceScars = new GpuSurfaceScars(groundDamage);
    private long scarSurfaceRevision;
    private boolean combatScars = GpuGroundDamage.COMBAT_SCARS;
    /** A board the editor changes, and its samples: any surface may appear, so programs keep the biome stage. */
    private boolean editable;
    /**
     * An editor board waits behind its loading stage for the palette's kinds (GpuEditorTerrainPreviews.kinds), which
     * {@link #kinds}, a private renderer, builds; their materials then join this terrain's warm-up (takeKinds).
     */
    private boolean kindsPending;
    private GpuTerrain kinds;
    private final ArrayDeque<BoardScene> kindScenes = new ArrayDeque<>();
    /** False for {@link #kinds}: it builds materials, and links none of their programs. */
    private boolean linking = true;
    private final GpuPropBatch propBatch = new GpuPropBatch();
    private final GpuFungus fungus = new GpuFungus();
    private final GpuGeysers geysers = new GpuGeysers();
    private BoardScene coverScene;
    /** GL-thread cache derived from the immutable source tiles; published with the existing terrain rebuild. */
    private List<BoardScene.Tile> lunarSource, lunarTiles;
    private boolean lunarWeightless;
    /** Derived decorative scatter, rebuilt once per source snapshot or nuclear mask change. */
    private List<BoardScene.Tile> scatterSource, scatterTiles;
    private long scatterScorchRevision = -1;
    private int scatterBoardId;
    private final Vector3 wind = new Vector3();
    // Pure CPU geometry may follow model lifetime; native meshes always have explicit render-thread owners.
    private final Map<Model, List<Vector3>> featureTriangles = new java.util.WeakHashMap<>();
    private final BoundingBox shadowBounds = new BoundingBox();
    private final UnitBounds.Frame frameBounds;
    private List<BoardScene.Tile> tiles;
    private Map<Coords, BoardFlow.Current> currents = Map.of();
    private BoardConcrete coast;
    private BoardScene.Light light;
    private BoardAtmosphere.Lighting atmosphere;
    private float clock;
    private long shadingPass;
    private float vegetationPhase;
    /** Render-owned flow time, advanced only by animate: gravity changes speed without restarting the pattern. */
    private float magmaClock;
    private float floor;
    private int chunkRows;
    private float buildingOpacity = DEFAULT_BUILDING_OPACITY;
    private boolean normalMaps = true;
    private boolean terrainParallax = true;
    private boolean grass = true;
    private boolean waterEffects = true;
    /** This frame's visual gravity, converted from the scenario's g multiplier to metres per second squared. */
    private float gravity = BoardAtmosphere.STANDARD_GRAVITY;
    private boolean vacuum;
    private float wetness;
    private float detailPixelsPerUnit = Float.NaN;
    /** Small placed objects hide when zoomed out ({@link TreeLod#SMALL_OBJECTS}); only tests switch this off. */
    private boolean smallObjects = true;
    /** Derived per camera by updateDetail: this view skips small objects. Presentation only, never game state. */
    private boolean smallHidden;
    /** Editor objects that draw and pick at every zoom: the selection and those hovered in Layers or the side view. */
    private Set<String> pinned = Set.of();
    private boolean hasCutaways;
    /** The 3D Graphics the next build is for: the Classic columns, or the 3D relief ({@link #setColumns}). */
    private boolean columns;
    /** Whether the installed chunks are the Classic columns; set when a build commits. */
    private boolean installedColumns;
    /**
     * The Tuning panel's Tessellation switch: the next build puts the relief that curves in parts of its own, which
     * tessellate ({@link #sculptedTriangles}); a switch rebuilds every chunk, as the 3D Graphics do.
     */
    private boolean tessellation;
    /** The switch the installed chunks were built for; set when a build commits. */
    private boolean installedTessellation;
    /** Curving copies of installed sculpted materials, kept so their family programs build with the others. */
    private final List<Material> curvedFamilies = new ArrayList<>();
    /**
     * The graphics the ground and decal atlases are packed for, set when a build packs them: a switch reversed after
     * that has to rebuild every chunk, as a switch does.
     */
    private boolean atlasColumns;
    /** The Tactical View's draw rule ({@link #setFluffHidden}); it never rebuilds the terrain. */
    private boolean fluffHidden;
    /**
     * What the depth pre-pass draws (renderPrepass): ground relief and solid plant parts with an empty fragment stage,
     * cutout leaves and impostor cards with their own alpha test. Native measurements take one category at a time.
     */
    /**
     * OBJECTS: building shells' opaque model surfaces (model.frag, GpuModelMaterial.occluder), whose draw order leaves
     * overdraw. CLIFFS: the relief of a chunk whose walls pass cliffWalls; RELIEF: the relief of the other chunks.
     */
    enum Prepass { RELIEF, CLIFFS, FOLIAGE, IMPOSTORS, OBJECTS }

    /**
     * The categories the depth pre-pass draws: the trees' leaves and impostor cards, whose cutouts hide the most shading,
     * the relief of cliff-heavy chunks, whose walls hide ground behind them, and building shells, which hide each
     * other, the trees and the ground (not while a hex is under TreeLod.PREPASS_OBJECTS pixels).
     * {@code -Dmegamek.gpu.depthPrepass=false} turns it off; {@code =all} adds the other chunks' relief, for measurements
     * only (on an Iris Xe their twins cost what they save). Twins build behind the loading stage for the categories set
     * then; native checks also switch it between frames.
     */
    static Set<Prepass> depthPrepass = switch (System.getProperty("megamek.gpu.depthPrepass", "")) {
        case "false" -> EnumSet.noneOf(Prepass.class);
        case "all" -> EnumSet.allOf(Prepass.class);
        default -> EnumSet.of(Prepass.CLIFFS, Prepass.FOLIAGE, Prepass.IMPOSTORS, Prepass.OBJECTS);
    };
    /**
     * A chunk's relief is cliffs (Prepass.CLIFFS) when its walls, each relief triangle's area as seen from beside it,
     * cover more than this share of its hexes' area (MeshBatch.wallArea): the Box Canyon's and the calibration board's
     * cliff bay's chunks (.46 to 2), not grassland hills or a city (at most .21). Native measurements set it before a
     * build.
     */
    static float cliffWalls = .45f;
    /** The colour pass's depth test for a draw the pre-pass drew; native measurements compare GL_LEQUAL. */
    static int prepassDepthFunction = GL20.GL_EQUAL;
    /**
     * Sculpted ground leaves out the biome stage on a board without biomes that the editor does not change
     * (GpuEnvironmentLighting.biomes). Native checks turn it off to compare frames of the complete programs.
     */
    static boolean pruning = true;
    /**
     * Each generic colour program's depth twin (both libGDX shaders, never shader-manager handles), paired with its warm-up
     * sample behind the loading stage (prepareTerrainMaterials); its family programs use it (shaderGroup). A pair lives
     * until its program or its twin is disposed, as a shader edit replaces them; the warm-up then pairs the replacements.
     */
    private final Map<Shader, Shader> prepassTwins = new IdentityHashMap<>();
    /** Tree species whose stand parts are warm-up samples, so their programs and twins build behind the loading stage. */
    private final Set<String> standSpecies = new HashSet<>();
    /** Whether this flush's colour draws follow a depth pre-pass (render, bindMaterial). */
    private boolean prepassed;
    /**
     * Whether a hex is under TreeLod.PREPASS_OBJECTS pixels on screen (updateDetail): objects then draw without their
     * twins (twin), which stay built, so a zoom links nothing. Perspective views keep them.
     */
    private boolean objectsFar;
    /** The last pre-pass's twin draws, and the lists it reuses. */
    private int prepassDraws;
    private final Array<Renderable> prepassParts = new Array<>();
    private final Array<Shader> prepassOrder = new Array<>();
    private boolean terrainMaterialsReady;
    private GpuGlsl.Preparation terrainMaterialPreparation;
    /** Sculpt and blend ground switches to programs with constant families once those have compiled. */
    private final GpuFamilyPrograms familyPrograms;
    /** A load, graphics switch or lighting change whose family programs the loading stage still waits for. */
    private boolean familySession = true;
    private final List<Renderable> terrainMaterialSamples = new ArrayList<>();
    /**
     * The last warm-up poll's programs (prepareTerrainMaterials), each sample's generic program and depth twin, and
     * how many of them it found linked: the loading stage's shader bar reads them (shaderProgress).
     */
    private int terrainPrograms, terrainProgramsLinked;
    /** The samples and light of the last poll that asked every sample, none cut by the frame budget; null before one. */
    private List<Object> terrainMaterialsAsked;
    private long terrainMaterialEnvironment = -1;
    private int terrainMaterialFlags;
    private boolean terrainMaterialShadows;

    /** Tiny shared, mipmapped field: mask and sky variation; allocated once, never updated per frame. */
    private static Texture rainNoise() {
        Pixmap pixels = new Pixmap(64, 64, Pixmap.Format.RGBA8888);
        Random random = new Random(0x7261696eL);
        Random waterRandom = new Random(0x7761746572L);
        try {
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    int value = random.nextInt(256);
                    // Keep the existing red-channel puddle field; water borrows independent green/blue noise.
                    pixels.drawPixel(x, y, (value << 24) | (waterRandom.nextInt(256) << 16) | (waterRandom.nextInt(256) << 8) | 255);
                }
            }
            Texture texture = new Texture(pixels, true);
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            texture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
            return texture;
        } finally {
            pixels.dispose();
        }
    }

    /** A custom lit surface's fragment shader: its source with cloud shadows and the shared functions. */
    static String litFragment(String file) {
        String source = GpuShaderSource.read(file);
        // Compose shared lighting first so cloud modulation reaches the actual lighting function, not just main.
        return GpuIceShader.fragment(GpuCloudShadow.fragment(GpuShaderSource.terrainLighting(source)));
    }

    private static boolean linearDiffuse(Material material) {
        var diffuse = material.get(TextureAttribute.class, TextureAttribute.Diffuse);
        return diffuse != null && GpuColorTexture.isColor(diffuse.textureDescription.texture);
    }

    /** Whether a sculpted material has a curving copy where its chunk is built for tessellation (sculptedTriangles). */
    private static boolean curvable(Material material) {
        return GpuTessellation.available() && (material.has(Sculpt.TYPE) || material.has(GpuRoads.Mask.TYPE))
              && !material.has(GpuMagmaShader.TYPE)
              && !GpuTessellation.curved(material) && !GpuMaterialCache.isWall(material);
    }

    /**
     * Sculpted relief built as curving (GpuTessellation#curved) draws tessellated, colour and depth twin alike; shadows
     * draw its flat triangles, which the curve only ever rises above (tessellation-evaluation.glsl).
     */
    private static boolean tessellates(Renderable renderable) {
        return GpuTessellation.available() && GpuTessellation.curved(renderable.material)
              && !GpuTreeInstances.instanced(renderable);
    }

    /**
     * Non-mask distinctions shared by live shaders and their warm-up samples. Water modes and magma flow share an
     * attribute mask but use different programs, including on shader reload.
     */
    private static boolean sameMaterialVariant(Material material, int iceMode, boolean linearTexture,
          int ordinaryFamily, GpuWaterShader.Mode waterMode, boolean flowingMagma) {
        return GpuIceShader.mode(material) == iceMode && linearDiffuse(material) == linearTexture
              && GpuSurfaceBlend.ordinaryFamily(material) == ordinaryFamily && waterMode(material) == waterMode
              && flowingMagma(material) == flowingMagma;
    }

    private static GpuWaterShader.Mode waterMode(Material material) {
        var water = material.get(GpuWaterShader.class, GpuWaterShader.TYPE);
        return water == null ? null : water.mode;
    }

    private static boolean flowingMagma(Material material) {
        var magma = material.get(GpuMagmaShader.class, GpuMagmaShader.TYPE);
        return magma != null && magma.flowing();
    }

    /** The per-instance attribute layout, which DefaultShader.canRender does not compare; 0 when not instanced. */
    private static long instanceMask(Renderable renderable) {
        VertexAttributes instances = renderable.meshPart.mesh.getInstancedAttributes();
        return instances == null ? 0 : instances.getMaskWithSizePacked();
    }

    GpuTerrain() {
        this((GpuUnitModels) null);
    }

    /** Lets native integration checks exercise the GIF fallback without changing the production constant. */
    GpuTerrain(boolean liquidShaderAnimation) {
        this(liquidShaderAnimation, false);
    }

    /** Native A/B checks share all effects and differ only in the source of the water color. */
    GpuTerrain(boolean liquidShaderAnimation, boolean proceduralWater) {
        this(null, null, liquidShaderAnimation, proceduralWater);
    }

    /** The battle view owns this library; terrain borrows the equipment buffers without disposing them. */
    GpuTerrain(GpuUnitModels unitModels) {
        this(unitModels, null);
    }

    /** Borrow the battle view's bounds only between its post-animation begin and the end of that render frame. */
    GpuTerrain(GpuUnitModels unitModels, UnitBounds.Frame frameBounds) {
        this(unitModels, frameBounds, GpuLiquidShader.USE_SHADER_ANIMATION, GpuWaterShader.USE_PROCEDURAL_WATER);
    }

    /**
     * A second renderer, such as the editor's sample previews, drawing with {@code lender}'s decoded models and
     * textures: it never decodes its own copies on the render thread and never disposes them. Its commits retire only
     * generated models that neither renderer draws nor has taken for an unfinished build ({@link GpuAssets#take}).
     */
    GpuTerrain(GpuTerrain lender) {
        this(null, null, GpuLiquidShader.USE_SHADER_ANIMATION, GpuWaterShader.USE_PROCEDURAL_WATER, lender.assets);
        // Samples and prints take no combat; an editor's samples show any surface.
        combatScars = false;
        editable = lender.editable;
    }

    private GpuTerrain(GpuUnitModels unitModels, UnitBounds.Frame frameBounds, boolean liquidShaderAnimation, boolean proceduralWater) {
        this(unitModels, frameBounds, liquidShaderAnimation, proceduralWater, null);
    }

    private GpuTerrain(GpuUnitModels unitModels, UnitBounds.Frame frameBounds, boolean liquidShaderAnimation, boolean proceduralWater,
          GpuAssets borrowed) {
        this.unitModels = unitModels;
        this.frameBounds = frameBounds;
        this.liquidShaderAnimation = liquidShaderAnimation;
        this.proceduralWater = proceduralWater;
        environmentLighting.set(BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS));
        environment.set(environmentLighting);
        borrowedAssets = borrowed != null;
        familyPrograms = new GpuFamilyPrograms(!borrowedAssets, Sculpt.TYPE, GpuTerrain::familyVariant);
        assets = borrowedAssets ? borrowed : new GpuAssets();
        // The material arrays decode on the common pool while the view is made, before the first terrain draw.
        if (!borrowedAssets) {
            assets.prepareTerrainArrays(SCULPT_LAYERS);
            materialCache.prepare();
        }
        lavaLighting = new GpuLavaLighting(assets);
        environmentLighting.lavaLighting = lavaLighting;
        trees = new GpuTreeInstances((name, level) -> foliage(assets.lodModel(name, level)));
    }

    private static final class Prop {
        private boolean infrastructure;
        private final Coords coords;
        private final BoundingBox bounds;
        private final String treeAsset;
        /** What picking and placement hit: the near level (LOD0) whatever level draws. */
        private Model pickingModel;
        /** The triangles picked instead of {@link #pickingModel}'s, for an instance of only some of its nodes; or null. */
        private List<Vector3> pickingTriangles;
        /**
         * A placed object's mesh per level ({@link GpuAssets#objectLevels}), its near mesh first; null when it has one.
         * {@link #detail} chooses among them by the buildings' rule.
         */
        private List<Model> levels;
        private final float treeDiameter;
        private ModelInstance instance;
        private final boolean hardSurface;
        private final GpuBuilding.Assembly building;
        /** A building's, fuel tank's or industrial structure's shell or floor, or a placed object other than a plant. */
        private final boolean structure;
        /**
         * Terrain with gameplay value that the Tactical View keeps ({@link GpuTerrain#setFluffHidden}): a bridge, rough,
         * rubble and fortifications, a tunnel's portal.
         */
        private boolean tactical;
        /** Height-only industrial cover has neither occupiable storeys nor building cutaways. */
        private final boolean industrial;
        /** Cosmetic cover, including instanced fungi; excluded from wireframe fill and lines. */
        private boolean scatter;
        /** A tree of the hex's woods or jungle cover (no scatter, no placed plant): the Tactical View keeps it. */
        private boolean cover;
        /** A placed object under a quarter hex, which zoomed-out views skip ({@link GpuTerrain#drawn}). */
        private boolean small;
        /**
         * A placed object's id, which editor picking, previews and pinning use in every view; null for one its terrain or
         * rail owns ({@link BoardFeatures#terrainOwned}) and for every other prop.
         */
        private String decorationId;
        private float decorationAnchorZ;
        /**
         * A placed object's feature, owned ones included: in every view its parts draw instanced
         * ({@link Chunk#cacheDecorations}), never in the packed prop caches. Null for every other prop.
         */
        private BoardScene.Feature decorationFeature;
        private BoardScene.Feature previewFeature;
        // Render-thread presentation only. Rebuilds continue borrowing the untouched installed instance/bounds.
        private ModelInstance previewInstance;
        private BoundingBox previewBounds;
        private float previewAnchorZ;
        private Coords previewCoords;
        private String receiver;
        /** World support height for an interior floor; NaN for shells and other props. */
        private final float floorZ;
        private ModelInstance hoverOpaque;
        /** The drawn level of a building's or placed object's mesh ({@link #detail}). */
        private int lod;

        Prop(Coords coords, ModelInstance instance, BoundingBox bounds, String treeAsset, boolean hardSurface) {
            this(coords, instance, bounds, treeAsset, hardSurface, null, false);
        }

        Prop(Coords coords, ModelInstance instance, BoundingBox bounds, String treeAsset, boolean hardSurface,
              GpuBuilding.Assembly building, boolean structure) {
            this(coords, instance, bounds, treeAsset, hardSurface, building, structure, Float.NaN);
        }

        Prop(Coords coords, ModelInstance instance, BoundingBox bounds, String treeAsset, boolean hardSurface,
              GpuBuilding.Assembly building, boolean structure, float floorZ) {
            this(coords, instance, bounds, treeAsset, hardSurface, building, structure, floorZ, false);
        }

        Prop(Coords coords, ModelInstance instance, BoundingBox bounds, String treeAsset, boolean hardSurface,
              GpuBuilding.Assembly building, boolean structure, float floorZ, boolean industrial) {
            this.coords = coords;
            this.building = building;
            this.structure = structure;
            this.industrial = industrial;
            this.floorZ = floorZ;
            this.instance = instance;
            this.bounds = bounds;
            this.treeAsset = treeAsset;
            this.hardSurface = hardSurface;
            pickingModel = building == null ? instance.model : null;
            treeDiameter = treeAsset == null ? 0 : bounds.getDimensions(new Vector3()).len();
        }

        /**
         * The one level rule of buildings and placed objects: the larger side of the placed bounds on screen against
         * {@link TreeLod#PROPS}, so both camera views choose alike. Picking stays on
         * the near level ({@link #pickingModel}); shadows draw the chosen level from the chunk's next cache.
         */
        boolean detail(float pixelsPerUnit) {
            int count = building != null ? building.lodCount() : levels == null ? 1 : levels.size();
            if (count < 2) { return false; }
            float pixels = Math.max(bounds.getWidth(), bounds.getHeight()) * pixelsPerUnit;
            lod = Math.min(count - 1, TreeLod.level(TreeLod.PROPS, pixels, lod));
            Model next = building != null ? building.model(lod) : levels.get(lod);
            if (instance.model == next) { return false; }
            instance = level(lod, instance);
            if (hoverOpaque != null) { updateHoverOpaque(); }
            return true;
        }

        /**
         * This placement drawn at mesh {@code level}: {@code source}'s transform, its colours for the shared mesh of each
         * level (RigidGlb#packed) and its material state.
         */
        private ModelInstance level(int level, ModelInstance source) {
            ModelInstance result = building != null ? building.instance(level) : new ModelInstance(levels.get(level));
            result.transform.set(source.transform);
            result.userData = source.userData;
            for (Material material : result.materials) {
                Material previous = source.getMaterial(material.id);
                if (building == null && previous != null) {
                    // An object's levels share its file's materials: all of the placement's material state carries over.
                    material.clear();
                    for (Attribute attribute : previous) { material.set(attribute.copy()); }
                    continue;
                }
                // Cutaway state belongs to the placement and survives a visual detail change. A material the source
                // lacks (an orchard tree's fruit, on its nearer levels only) takes it from another of the placement's
                // materials, with the placement's culling.
                Material state = previous != null ? previous : source.materials.isEmpty() ? null : source.materials.first();
                if (state == null) { continue; }
                for (long type : new long[] { BlendingAttribute.Type, DepthTestAttribute.Type, IntAttribute.CullFace, GpuBuildingCutaway.TYPE }) {
                    Attribute attribute = state.get(type);
                    if (attribute != null) { material.set(attribute.copy()); }
                }
                if (previous == null) { culling(result, material, state.has(BlendingAttribute.Type)); }
            }
            return result;
        }

        /** What the editor's side view draws: the near level, whatever level the board's camera chose. */
        ModelInstance near() { return lod == 0 ? instance() : level(0, instance()); }

        private void updateHoverOpaque() {
            hoverOpaque = copy(instance);
            for (Material material : hoverOpaque.materials) {
                var clip = material.get(GpuBuildingCutaway.class, GpuBuildingCutaway.TYPE);
                // Every material of a hovered placement carries the clip; a missing one draws uncut, never throws.
                if (clip != null) { material.set(new GpuBuildingCutaway(clip.floor, clip.ceiling, false)); }
                material.remove(BlendingAttribute.Type);
                material.remove(DepthTestAttribute.Type);
                culling(hoverOpaque, material, false);
            }
        }

        Coords coords() { return previewCoords == null ? coords : previewCoords; }
        ModelInstance instance() { return previewInstance == null ? instance : previewInstance; }
        BoundingBox bounds() { return previewInstance == null ? bounds : previewBounds; }
        float anchorZ() { return previewInstance == null ? decorationAnchorZ : previewAnchorZ; }
        boolean tree() { return treeAsset != null; }
        /** A route marker, rail or maglev: its track is cached with its chunk in every view and never fades. */
        boolean route() { return decorationFeature != null && BoardRail.route(decorationFeature); }
        /**
         * A tree drawn by the chunk's instanced stand. Its installed pose was built for the stand; an editor preview
         * the instance data cannot hold (a tilt or mirror) draws as an object part until the rebuild.
         */
        boolean standing() {
            return tree() && (previewInstance == null || GpuTreeInstances.placeable(previewInstance.transform));
        }

    }
    /** An animated liquid material: its frames, whether it falls, and the current it moves with. */
    record LiquidSurface(Material material, BoardLiquid.Textures source, boolean falling, BoardFlow.Current current) { }

    /**
     * Marks exposed ground and carries its water-film response; negative excludes snow, ice and water. {@code cliffs}
     * marks a cliff-heavy chunk's relief (Prepass.CLIFFS): its draws stay apart from other chunks' in shared pages and
     * batches, and share their programs.
     */
    private static final class Ground extends FloatAttribute {
        static final long TYPE = register("boardGround");
        final boolean cliffs;

        Ground(float response) {
            this(response, false);
        }

        Ground(float response, boolean cliffs) {
            super(TYPE, response);
            this.cliffs = cliffs;
        }

        @Override
        public Ground copy() {
            return new Ground(value, cliffs);
        }

        @Override
        public int hashCode() {
            return 31 * super.hashCode() + (cliffs ? 1 : 0);
        }

        @Override
        public int compareTo(Attribute other) {
            int order = super.compareTo(other);
            return order != 0 || other.type != type ? order : Boolean.compare(cliffs, ((Ground) other).cliffs);
        }
    }

    /** Ultra-sublevel caps have no lighting response; their distinct mask isolates the black shader. */
    private static final class Pit extends FloatAttribute {
        static final long TYPE = register("boardPit");

        Pit() { super(TYPE, 1); }

        @Override
        public Pit copy() { return new Pit(); }
    }

    /**
     * Bridge asphalt uses the road's world UVs, even when its model instance rotates or changes length. The value is
     * the road texture's coverage: its world-space repeat widens with its texture (GpuAssets.coverage).
     */
    private static final class BridgeDeck extends FloatAttribute {
        static final long TYPE = register("boardBridgeDeck");
        BridgeDeck(float coverage) { super(TYPE, coverage); }

        @Override
        public BridgeDeck copy() { return new BridgeDeck(value); }
    }

    /** Foliage shading: solid, canopy, snow or fungal tissue (its fruiting bodies and mycelium). */
    private static final class Foliage extends FloatAttribute {
        static final long TYPE = register("boardFoliage");

        Foliage(float part) { super(TYPE, part); }

        @Override
        public Foliage copy() { return new Foliage(value); }
    }

    /**
     * Impostor cards and their plant's crown radius in model units. A card stands in the middle of its crown, so its
     * shadow is looked up a crown radius toward the light: its own cards and crown cannot shade it, other casters can.
     */
    private static final class Impostor extends FloatAttribute {
        static final long TYPE = register("boardImpostor");

        Impostor(float radius) { super(TYPE, radius); }

        @Override
        public Impostor copy() { return new Impostor(value); }
    }

    /** Plant material roles are authored in the GLB, so new plant assets need no editor-specific ID mapping. */
    static boolean foliageModel(Model model) {
        for (Material material : model.materials) {
            if (material.id.startsWith("bark") || material.id.startsWith("canopy") || material.id.equals("cactus")
                  || material.id.equals("fungus") || material.id.equals("mycelium") || material.id.equals("coral")) { return true; }
        }
        return false;
    }

    /** Marks a tree model's materials for the foliage shader, once; instances copy the marks. */
    private static Model foliage(Model model) {
        for (Material material : model.materials) {
            // A missing model's shared marked box stays a plain prop wherever it stands in (GpuAssets#missing).
            if (material.id.equals(GpuAssets.MISSING)) { continue; }
            if (!material.has(Foliage.TYPE)) {
                boolean solid = material.id.startsWith("bark") || material.id.equals("cactus") || material.id.equals("fruit")
                      || material.id.equals("fungus") || material.id.equals("mycelium") || material.id.equals("coral");
                material.set(new Foliage(material.id.equals("fungus") || material.id.equals("mycelium") ? 3
                      : material.id.equals("snow") || material.id.equals("canopy-snow-cutout") ? 2 : solid ? 0 : 1));
            }
            // The impostor cards are cutouts: their texels outside the plant discard in every pass, shadows included.
            // They draw as opaque (unblended, sorted with the solid trees), so they too hide the ground beneath first.
            if (material.id.equals("impostor")) {
                BoundingBox bounds = model.calculateBoundingBox(new BoundingBox());
                material.set(new BlendingAttribute(false, GL20.GL_ONE, GL20.GL_ZERO, 1),
                      new FloatAttribute(FloatAttribute.AlphaTest, .5f),
                      new Impostor(Math.max(bounds.getWidth(), bounds.getHeight()) / 2));
            }
        }
        return model;
    }

    /**
     * Whether the terrain shader ladder ({@code batch}'s createShader) draws this material with the plain or foliage
     * shader, which have instanced variants; pit, ground, sculpt, water, magma, liquid and vegetation shaders do not. The
     * ladder decides by this predicate first, so instancing and shader choice cannot disagree.
     */
    private static boolean instancedShader(Material material) {
        long mask = material.getMask();
        if ((mask & (Pit.TYPE | GpuBackdrop.TYPE | GpuBiomeVegetation.Kind.TYPE | GpuGroundCover.Wind.TYPE)) != 0
              || (mask & GpuMagmaShader.TYPE) != 0 && (mask & Sculpt.TYPE) == 0) { return false; }
        return (mask & Foliage.TYPE) != 0
              || (mask & (Sculpt.TYPE | Ground.TYPE | GpuWaterShader.TYPE | GpuLiquidShader.Frame.TYPE)) == 0;
    }

    /** Canonically sculpted tops and cliffs of one surface family; per-vertex data carries the rest. */
    static final class Sculpt extends FloatAttribute {
        static final long TYPE = register("boardSculpt");

        Sculpt(float family) { super(TYPE, family); }

        @Override
        public Sculpt copy() { return new Sculpt(value); }
    }

    /**
     * The four materials of each surface family, by ordinal: ground on open tops, debris on rims, cliff feet, talus
     * and slopes, the wall on cliffs and the rock kit, and the mantle that covers low steps of grassland (earth),
     * snowfields (snow) and pavement (cast concrete) and caps their high cliffs. Names refer to
     * {@code textures/terrain/surfaces}.
     */
    private static final String[][] SCULPT_MATERIALS = {
          { "grassland-ground", "temperate-debris", "temperate-cliff", "temperate-bank" },
          { "dirt-ground", "dirt-debris", "temperate-cliff", "temperate-bank" },
          { "sand-ground", "arid-debris", "arid-cliff", "arid-cliff" },
          { "rock-ground", "temperate-debris", "temperate-cliff", "temperate-cliff" },
          { "concrete-ground", "temperate-debris", "temperate-cliff", "concrete-bank" },
          { "snow-ground", "temperate-debris", "temperate-cliff", "snow-ground" },
          { "lunar-ground", "lunar-debris", "lunar-cliff", "lunar-cliff" },
          { "fungus-ground", "fungus-debris", "fungus-cliff", "fungus-bank" },
          { "desert-ground", "arid-debris", "arid-cliff", "desert-cap" },
          { "mars-ground", "mars-ground", "mars-cliff", "mars-cap" },
          { "volcano-ground", "volcano-ground", "volcano-cliff", "volcano-cap" },
          { "tropical-ground", "dirt-ground", "temperate-cliff", "temperate-bank" },
    };

    /** A surface family's ground material, as its open tops are drawn: a name in {@code textures/terrain/surfaces}. */
    static String groundMaterial(BoardScene.Surface family) {
        return SCULPT_MATERIALS[family.ordinal()][0];
    }

    /** Whether sunken ground (level -1 and below) takes its family's floor material from {@link #FLOORS}. */
    static final boolean SUBLEVEL_FLOORS = true;

    /** A family's floor: its material, and whether it shows only where the ground thins out first (a hollow's edges). */
    private record Floor(String material, boolean edges) { }

    /**
     * Each surface family's floor, by ordinal: what gathers in its hollows, in place of its ground from level -1 down
     * (terrain-materials.glsl floorShare). Snow stays snow but where it melts first: thin snow over frozen ground in
     * broken tongues along the banks' feet.
     * Null keeps the ground: concrete stays paved, sand stays rippled sand (its level grade marks the hollow).
     */
    private static final Floor[] FLOORS = {
          new Floor("grassland-floor", false), new Floor("dirt-floor", false), null,
          new Floor("rock-floor", false), null, new Floor("snow-floor", true), new Floor("lunar-floor", false),
          new Floor("fungus-floor", false), new Floor("desert-floor", false), new Floor("mars-floor", false),
          new Floor("volcano-floor", false), new Floor("tropical-floor", false),
    };

    private static final List<String> SCULPT_LAYERS = java.util.stream.Stream.of(
          Arrays.stream(SCULPT_MATERIALS).flatMap(Arrays::stream),
          java.util.stream.Stream.of("tundra-crust", "tundra-ground", "tundra-mat", "biome-soil",
                "tundra-ground-variant",
                "concrete-mulch", "ground-scorch", "biome-mud"),
          Arrays.stream(FLOORS).filter(floor -> SUBLEVEL_FLOORS && floor != null).map(Floor::material))
          .flatMap(names -> names).distinct().toList();
    /**
     * The board materials whose finish the terrain reads from arrays (finish-arrays.glsl): the sculpt layers, at their
     * index, then the road materials, at ROAD_FINISH plus theirs.
     */
    private static final List<String> FINISHES = java.util.stream.Stream.concat(
          SCULPT_LAYERS.stream().map(name -> "terrain/surfaces/" + name), GpuRoads.MATERIALS.stream().map(name -> "roads/" + name))
          .toList();

    /**
     * Every family's colour/height and normal/AO arrays. Stable semantic layer IDs retain the existing
     * even-colour/odd-normal numbering; sampling helpers map them into the two compact arrays.
     */
    private static final class SculptLayers extends Attribute {
        static final long TYPE = register("boardSculptLayers");
        final GpuAssets.MaterialArrays texture;
        final float[] layers;

        SculptLayers(GpuAssets.MaterialArrays texture, float... layers) {
            super(TYPE);
            this.texture = texture;
            this.layers = layers.clone();
        }

        @Override
        public SculptLayers copy() { return new SculptLayers(texture, layers); }

        @Override
        public int compareTo(Attribute other) {
            if (type != other.type) { return Long.compare(type, other.type); }
            var maps = (SculptLayers) other;
            int order = Integer.compare(System.identityHashCode(texture), System.identityHashCode(maps.texture));
            return order != 0 ? order : Arrays.compare(layers, maps.layers);
        }

        @Override
        public int hashCode() { return 31 * (31 * super.hashCode() + System.identityHashCode(texture)) + Arrays.hashCode(layers); }
    }

    /** Metres per repeat of the ground, debris, wall and mantle maps. */
    private static final class SculptTiles extends Attribute {
        static final long TYPE = register("boardSculptTiles");
        final float[] metres;

        SculptTiles(float... metres) {
            super(TYPE);
            this.metres = metres.clone();
        }

        @Override
        public SculptTiles copy() { return new SculptTiles(metres); }

        @Override
        public int compareTo(Attribute other) {
            if (type != other.type) { return Long.compare(type, other.type); }
            return Arrays.compare(metres, ((SculptTiles) other).metres);
        }

        @Override
        public int hashCode() { return 31 * super.hashCode() + Arrays.hashCode(metres); }
    }

    /** Index ranges borrow the chunk's meshes; no duplicate geometry or construction-time scene is retained. */
    /** {@code lift}: a Solaris elevator's lift, which a moving platform draws at its own height (Lifts). */
    record TileRange(int layer, Mesh mesh, Material material, int offset, int count, boolean route, boolean lift) { }
    private record PaintedDecal(BoardDecals.Stamp stamp, List<BoardSurface.Face> faces, float lift) { }

    private record InteriorStruts(Coords coords, ModelInstance instance) { }

    static final class TileMesh {
        // The finished support, kept while the chunk is, as engines keep a loaded chunk's collision: picking, draping,
        // placement and replanting read it, and no query rebuilds an installed hex.
        BoardTacticalGeometry.Surface support;
        // Its water geometry, which a rebuild reusing this hex takes over.
        BoardSurface.WaterGeometry water;
        // Ground cover planted on this tile's support while its chunk was prepared; null when it grows none.
        BoardPlants plants;
        BoardBridge.Shape bridgeShape;
        final List<TileRange> ranges = new ArrayList<>();
        final List<Prop> props = new ArrayList<>();
        final List<PaintedDecal> paint = new ArrayList<>();
        final List<InteriorStruts> struts = new ArrayList<>();
        /** Its multi-hex building share's glass (BoardMultiHexBuilding#glass), drawn with the transparent features. */
        final List<ModelInstance> glass = new ArrayList<>();
        final BoundingBox bounds = new BoundingBox().inf();
        float scatterDiameter;
    }

    private static final class Chunk implements Disposable {
        TerrainLod lod;
        /** A Classic chunk: its opaque layer holds the tileset columns, its tiles' support is their column. */
        boolean columns;
        /** Built with the Tessellation switch on: at full detail its relief that curves tessellates (sculpt). */
        boolean tessellated;
        boolean paintOnlyUpdate;
        final Map<Coords, TileMesh> tileMeshes = new HashMap<>();
        final List<ModelInstance> opaque = new ArrayList<>();
        Array<Renderable> terrainRenderables;
        /**
         * The ground material cache's tops (GpuMaterialCache.top) and charted walls (GpuMaterialCache.wall), not in
         * terrainRenderables; the tops' bounds, and the walls' on the board and in the wall region (their charts').
         */
        final Array<Renderable> topRenderables = new Array<>();
        final BoundingBox topBounds = new BoundingBox(), wallWorld = new BoundingBox().inf();
        final BoundingBox wallBounds = new BoundingBox().inf();
        /** Where its hexes' walls lie in the cache's wall region (GpuMaterialCache.Charts), from game data alone. */
        GpuMaterialCache.Charts charts;
        /**
         * Whether its tops follow this detail: a theme boundary's covers its sampling, a slope's coverage its
         * triangles, a wall's its chart. Their pages rebuild after a detail change; until then they draw cached only
         * from pages begun since their install, its generation (GpuMaterialCache#detailChanged).
         */
        boolean detailTops;
        int cacheSince;
        GpuTerrainDepth depthTerrain;
        /** Packed and instanced cosmetic cover share one visibility decision in every pass. */
        final List<ModelInstance> scatter = new ArrayList<>();
        final GpuTreeInstances.Stand scatterStand = new GpuTreeInstances.Stand();
        float scatterDiameter;
        boolean scatterVisible = true;
        final List<ModelInstance> overlays = new ArrayList<>();
        final List<ModelInstance> surfaceDecals = new ArrayList<>();
        final List<ModelInstance> tilesetDecals = new ArrayList<>();
        final List<ModelInstance> authoredPaint = new ArrayList<>();
        /** The paint pages' persistent view of authoredPaint; null until drawn after the paint changed. */
        Array<Renderable> paintRenderables;
        final List<ModelInstance> water = new ArrayList<>();
        Array<Renderable> waterRenderables;
        /** The far sides of the board-edge sections among waterRenderables, for what lies beyond the board. */
        final Array<Renderable> beyondRenderables = new Array<>();
        final List<LiquidSurface> liquidMaterials = new ArrayList<>();
        final List<Prop> props = new ArrayList<>();
        final List<Prop> cutaways = new ArrayList<>();
        /** Transparent board materials' instances (multi-hex buildings' glass): no depth writes, no shadows, no picking. */
        final List<ModelInstance> glass = new ArrayList<>();
        final GpuTreeInstances.Stand stand = new GpuTreeInstances.Stand();
        /** The stand's cover trees alone, which the Tactical View draws ({@link Prop#cover}). */
        final GpuTreeInstances.Stand coverStand = new GpuTreeInstances.Stand();
        final List<GpuFungus.Emitter> spores = new ArrayList<>();
        final List<GpuGeysers.Emitter> geysers = new ArrayList<>();
        final List<GpuLavaLighting.Source> fungalLights = new ArrayList<>();
        float treeDiameter;
        int treeLod;
        final List<InteriorStruts> struts = new ArrayList<>();
        final Array<Renderable> propRenderables = new Array<>();
        Array<Renderable> scatterRenderables = new Array<>();
        final Array<Renderable> shadowPropRenderables = new Array<>();
        final Array<Renderable> sharedProps = new Array<>();
        final Array<Renderable> sharedShadows = new Array<>();
        /** Placed object parts drawn with instancing, like shared building modules (mirror images too). */
        final Array<Renderable> decorationRenderables = new Array<>();
        /** Placed object parts whose pose (tilted, sheared, unevenly stretched) or material the instance data cannot hold. */
        final Array<Renderable> looseDecorations = new Array<>();
        /** The object of each part above, in the same order: every pass asks whether it is drawn. */
        final Array<Prop> decorationOwners = new Array<>(), looseOwners = new Array<>();
        final Array<Renderable> routeRenderables = new Array<>();
        List<BoardScene.Tile> editorTiles;
        /** Placed objects' props by object id, so an editor preview reads only the objects that changed. */
        final Map<String, List<Prop>> objectProps = new HashMap<>();
        /** Some placed object shows a preview pose until this chunk is replaced. */
        boolean previewed;
        final Set<Model> sharedInteriors = new HashSet<>();
        /**
         * The faded props, in the chunk's build order ({@link #cutaways}), set only where the fade changes. The blended
         * sort often cannot order them (libGDX's distance key, an int of 1000 x the squared camera distance, saturates and
         * ties for a distant camera), so it keeps their submission order: a stable one, not a hash set's, draws them alike
         * in every view and run.
         */
        Set<Prop> faded = Set.of();
        Coords hoverCoords;
        float hoverFloor = Float.NaN;
        final RenderableProvider solidProps = (out, pool) -> supply(
              faded.isEmpty() ? shadowPropRenderables : propRenderables, out);
        final RenderableProvider shadowProps = (out, pool) -> supply(shadowPropRenderables, out);
        /** The Tactical View's packed props ({@link #keptProps}); null until its first draw of this chunk. */
        private Array<Renderable> keptProps;
        final RenderableProvider kept = (out, pool) -> supply(keptProps(), out);
        final RenderableProvider routes = (out, pool) -> supply(routeRenderables, out);
        final BoundingBox bounds = new BoundingBox().inf();
        /** Shore distance, depth and current of this chunk's open water; null without any. */
        GpuWaterShader.Field waterField;
        /** Lava reuses the same current/bank sampler with its own connectivity and chunk-owned texture. */
        GpuWaterShader.Field lavaField;
        final GpuTextures<BoardScene.Pixels> roadMasks = new GpuTextures<>();

        private static void supply(Array<Renderable> source, Array<Renderable> out) {
            for (Renderable renderable : source) {
                renderable.shader = null;
                renderable.environment = null;
            }
            out.addAll(source);
        }

        /** Caches the props for drawing and shadows. The Tactical View draws {@link #keptProps} instead. */
        void cacheProps() {
            cacheDecorations();
            if (cutaways.isEmpty() && struts.isEmpty()) { return; }
            if (shadowPropRenderables.isEmpty()) {
                cacheProps(shadowPropRenderables, false, false);
                // A shadow always uses the original opaque materials, independently of live instance fading.
                opaqueMaterials(shadowPropRenderables);
                for (Renderable part : shadowPropRenderables) { part.material.remove(GpuBuildingCutaway.TYPE); }
            }
            // These snapshots contain transforms and mesh ranges only; module geometry is never merged per building.
            List<ModelInstance> shared = new ArrayList<>();
            for (Prop prop : cutaways) {
                if (prop.building != null) { shared.add(prop.instance); }
            }
            sharedShadows.clear();
            sharedShadows.addAll(GpuTerrainDepth.snapshot(shared));
            opaqueMaterials(sharedShadows);
            for (Renderable part : sharedShadows) { part.material.remove(GpuBuildingCutaway.TYPE); }
            for (Prop prop : faded) {
                shared.remove(prop.instance);
                if (prop.building != null && prop.hoverOpaque != null) { shared.add(prop.hoverOpaque); }
            }
            for (InteriorStruts strut : struts) {
                if (sharedInteriors.contains(strut.instance.model) && interiorVisible(strut.coords)) {
                    shared.add(strut.instance);
                }
            }
            for (Prop prop : cutaways) {
                if (!Float.isNaN(prop.floorZ) && sharedInteriors.contains(prop.instance.model)
                      && interiorFloorVisible(prop) && !faded.contains(prop)) { shared.add(prop.instance); }
            }
            sharedProps.clear();
            sharedProps.addAll(GpuTerrainDepth.snapshot(shared));
            // Stable, opaque material keys for the instancing cache; live cutaway materials can mutate independently.
            opaqueMaterials(sharedProps);
            GpuPropBatch.disposeMeshes(propRenderables);
            // Share the complete cache for normal rendering; only occupied chunks need a second mesh cache.
            if (!faded.isEmpty()) {
                cacheProps(propRenderables, true, false);
            }
        }

        /**
         * The Tactical View's packed props ({@link GpuTerrain#setFluffHidden}): the kept props only ({@link Prop#tactical}:
         * bridges, rough, rubble, fortifications, tunnel portals), no building, interior or placed object. Packed on the
         * view's first draw of this chunk and kept until the props are cached again, so T leaves the 3D caches and their
         * prop pages as they are in both directions.
         */
        Array<Renderable> keptProps() {
            if (keptProps == null) {
                keptProps = new Array<>();
                cacheProps(keptProps, false, true);
                opaqueMaterials(keptProps);
                for (Renderable part : keptProps) { part.material.remove(GpuBuildingCutaway.TYPE); }
            }
            return keptProps;
        }

        void disposeKeptProps() {
            if (keptProps != null) { GpuPropBatch.disposeMeshes(keptProps); }
            keptProps = null;
        }

        /**
         * Fills the plain tree stand from the chunk's standing trees at their drawn poses. New arrays, never edited in
         * place: the stand's buffers find a changed chunk by array identity, so an editor preview moves its trees.
         */
        void restand() {
            stand.trees.clear();
            coverStand.trees.clear();
            for (Prop prop : props) {
                if (!prop.scatter && prop.standing()) { stand.add(prop.treeAsset, prop.instance().transform); }
                if (prop.cover && prop.standing()) { coverStand.add(prop.treeAsset, prop.instance().transform); }
            }
        }

        /** The trees a pass draws: the whole stand, or in the Tactical View only the cover's ({@code fluff} hidden). */
        GpuTreeInstances.Stand trees(boolean fluff) { return fluff ? stand : coverStand; }

        /**
         * Placed objects, in every view: their parts borrow the shared asset meshes and draw instanced, so changing a
         * transform never repacks a static terrain mesh and a preview pose only changes this snapshot's transforms, which
         * each pass gathers again. Plants in the stand are drawn there; the Tactical View draws no placed object at all
         * ({@link GpuTerrain#drawn}). Route markers are cached with the chunk instead.
         */
        void cacheDecorations() {
            decorationRenderables.clear();
            looseDecorations.clear();
            decorationOwners.clear();
            looseOwners.clear();
            // Routes commit with their chunk. Dragging another object must not repack them.
            if (routeRenderables.isEmpty() && props.stream().anyMatch(Prop::route)) {
                GpuPropBatch.cache(routeRenderables, builder -> {
                    for (Prop prop : props) {
                        if (prop.route()) { builder.add(prop.instance()); }
                    }
                });
                opaqueMaterials(routeRenderables);
            }
            for (Prop prop : props) {
                if (prop.decorationFeature == null || prop.standing() || prop.route()) { continue; }
                // One snapshot per object: each part keeps its object, whose size class and pin every pass tests.
                Array<Renderable> parts = GpuTerrainDepth.snapshot(List.of(prop.instance()));
                opaqueMaterials(parts);
                for (Renderable part : parts) {
                    // A faded object's cut belongs to its transparent draw only.
                    part.material.remove(GpuBuildingCutaway.TYPE);
                    boolean instanced = GpuTreeInstances.instanceable(part.worldTransform) && instancedShader(part.material);
                    // An instanced mirror image keeps its model's culling: its batch flips the front face instead.
                    if (instanced) { culling(prop.instance().model, part.material, false, false); }
                    (instanced ? decorationRenderables : looseDecorations).add(part);
                    (instanced ? decorationOwners : looseOwners).add(prop);
                }
            }
        }

        private boolean interiorVisible(Coords coords) {
            return faded.stream().anyMatch(prop -> Float.isNaN(prop.floorZ) && prop.coords.equals(coords));
        }

        private boolean interiorFloorVisible(Prop floor) {
            if (!interiorVisible(floor.coords)) { return false; }
            boolean hoverOnly = cutaways.stream().anyMatch(prop -> prop.coords.equals(floor.coords) && prop.hoverOpaque != null);
            return !hoverOnly || Math.abs(floor.floorZ - hoverFloor) < .05f * BoardGeometry.hexScale();
        }

        private static void opaqueMaterials(Array<Renderable> renderables) {
            for (Renderable renderable : renderables) {
                renderable.material = new Material(renderable.material);
                // Alpha-tested GLB cards are opaque cutouts, not building transparency. LibGDX's shaders
                // require the blending attribute even when blending is disabled to apply their alpha test.
                if (renderable.material.has(FloatAttribute.AlphaTest)) {
                    renderable.material.set(new BlendingAttribute(false, GL20.GL_ONE, GL20.GL_ZERO, 1));
                } else { renderable.material.remove(BlendingAttribute.Type); }
                renderable.material.remove(DepthTestAttribute.Type);
                // Keep authored culling, including the reversed winding of mirrored native placements.
            }
        }

        /** Placed objects are never packed: {@link #cacheDecorations} draws them. {@code keptOnly}: {@link #keptProps}. */
        private void cacheProps(Array<Renderable> destination, boolean omitFaded, boolean keptOnly) {
            GpuPropBatch.cache(destination, builder -> {
                for (InteriorStruts strut : struts) {
                    if (!keptOnly && omitFaded && !sharedInteriors.contains(strut.instance.model)
                          && interiorVisible(strut.coords)) {
                        builder.add(strut.instance);
                    }
                }
                for (Prop prop : cutaways) {
                    if (prop.decorationFeature != null || keptOnly && !prop.tactical) { continue; }
                    if (omitFaded && prop.building == null && prop.hoverOpaque != null) { builder.add(prop.hoverOpaque); }
                    if (prop.building == null && !sharedInteriors.contains(prop.instance.model)
                          && (Float.isNaN(prop.floorZ) || omitFaded && interiorFloorVisible(prop))
                          && (!omitFaded || !faded.contains(prop))) {
                        builder.add(prop.instance());
                    }
                }
            });
        }

        @Override
        public void dispose() {
            GpuPropBatch.disposeMeshes(routeRenderables);
            GpuPropBatch.disposeMeshes(propRenderables);
            GpuPropBatch.disposeMeshes(shadowPropRenderables);
            disposeKeptProps();
            for (List<ModelInstance> layer : List.of(opaque, scatter, overlays, water, surfaceDecals, tilesetDecals, authoredPaint)) {
                layer.forEach(instance -> instance.model.dispose());
            }
            if (waterField != null) { waterField.dispose(); }
            if (lavaField != null) { lavaField.dispose(); }
            roadMasks.dispose();
        }
    }

    /** Blocking convenience for offline fixtures; uses the same preparation and installation path as the view. */
    void update(BoardScene scene) {
        update(scene, null);
        // Like the loading stage, wait for the load's family programs too: fixtures draw what a game shows.
        while (busy() || familiesPending()) {
            refine(null, true);
            if (busy() || familiesPending()) { java.util.concurrent.locks.LockSupport.parkNanos(1_000_000); }
        }
    }

    /** Request only the latest rendering snapshot. Game state and commands remain owned by the source. */
    void update(BoardScene scene, Camera camera) {
        if (lifts != null && !lifts.ending) { scene = scene.withTiles(lifts.lowered(scene)); }
        boolean weightless = gravity == 0;
        if (vacuum || weightless) {
            if (lunarSource != scene.tiles() || lunarWeightless != weightless) {
                lunarTiles = List.copyOf(scene.tiles().stream().map(tile -> tile.lunar(weightless)).toList());
                lunarSource = scene.tiles();
                lunarWeightless = weightless;
            }
            scene = scene.withTiles(lunarTiles);
        } else {
            lunarSource = null;
            lunarTiles = null;
        }
        if (scatterSource != scene.tiles() || scatterBoardId != scene.boardId()
              || scatterScorchRevision != groundDamage.scorchRevision()) {
            scatterSource = scene.tiles();
            scatterBoardId = scene.boardId();
            scatterScorchRevision = groundDamage.scorchRevision();
            BoardScene source = scene;
            scatterTiles = List.copyOf(scene.tiles().stream().map(tile -> groundDamage.scorched(source, tile)
                  ? tile.withoutVegetation() : tile).toList());
        }
        scene = scene.withTiles(scatterTiles);
        TerrainSettings settings = TerrainSettings.capture();
        if (kindsPending && kinds == null) {
            kinds = new GpuTerrain(this);
            kinds.linking = false;
            kindScenes.addAll(GpuEditorTerrainPreviews.kinds());
            kinds.update(kindScenes.removeFirst(), null);
        }
        BoardScene target = requested == null ? coverScene : requested.scene();
        TerrainSettings targetSettings = requested == null ? installedSettings : requested.settings();
        boolean targetColumns = requested == null ? installedColumns : requested.columns();
        boolean targetTessellated = requested == null ? installedTessellation : requested.tessellated();
        if (target == null || !sameBoard(target, scene) || !sameTerrain(target.tiles(), scene.tiles(), columns)
              || !sameSettings(targetSettings, settings) || targetColumns != columns
              || targetTessellated != tessellation
              || targetSettings.geometry().unitScale() != settings.geometry().unitScale()
                    && scene.tiles().stream().anyMatch(GpuTerrain::hasLimb)) {
            // Coalesce continuous painting into one latest snapshot without starving the build already underway.
            // Board/geometry-setting and graphics changes still cancel work whose result no longer applies.
            if (requested == null || !sameBoard(target, scene) || !sameSettings(targetSettings, settings)
                  || targetColumns != columns || targetTessellated != tessellation) { meshGeneration++; }
            // These show the loading stage: their family programs build behind it. Edits keep the built ones.
            if (target == null || !sameBoard(target, scene) || !sameSettings(targetSettings, settings)
                  || targetColumns != columns || targetTessellated != tessellation) {
                startFamilySession();
                curvedFamilies.clear();
                // Its models decode on the common pool while the board prepares, before the chunk builds draw them. A new
                // board's report lists what it draws as marked boxes (GpuAssets#takeMissing); a graphics switch adds none.
                if (!borrowedAssets) {
                    assets.prepareModels(featureModels(scene));
                    if (target == null || !sameBoard(target, scene)) { assets.newBoard(); }
                }
            }
            requested = new Request(meshGeneration, scene, settings, columns, tessellation);
            // Before anything prepares for it: whether its programs compile the biome stage.
            GpuBiomeSurface.mark(environment, editable || GpuBiomeSurface.active(scene));
            retiredChunks.addAll(detailCache.values());
            detailCache.clear();
        } else if (requested == null) {
            try (TerrainSettings.Scope ignored = TerrainSettings.use(settings)) {
                tiles = scene.tiles();
                coverScene = scene;
                GpuBiomeSurface.mark(environment, editable || GpuBiomeSurface.active(scene));
                installedSettings = settings;
                if (!Objects.equals(light, scene.light())) { updateLight(scene.light()); }
            }
        }
    }

    /**
     * The models {@code scene}'s features may draw (collectFeatures): not scatter (but fungus), boulders, rough ground,
     * shafts or decals.
     */
    private static java.util.Set<String> featureModels(BoardScene scene) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (BoardScene.Tile tile : scene.tiles()) {
            for (BoardScene.Feature feature : tile.features()) {
                var kind = feature.kind();
                if (kind == BoardScene.FeatureKind.SCATTER && !BoardFungus.asset(feature.asset())
                      || kind == BoardScene.FeatureKind.BOULDER || kind == BoardScene.FeatureKind.ROUGH
                      || kind == BoardScene.FeatureKind.SHAFT
                      || feature.decoration() != null && feature.decoration().kind().equals("decal")) { continue; }
                names.add(feature.asset());
            }
        }
        return names;
    }

    private boolean materialsReady() { return terrainMaterialsReady && sameMaterialConfiguration(); }

    /**
     * Whether the warm-up last prepared for this lighting configuration, with the palette's kinds of an editor board
     * (takeKinds): then at most an edit's new materials still link, and the board stays shown (ready).
     */
    private boolean sameMaterialConfiguration() {
        return !kindsPending && terrainMaterialEnvironment == environment.getMask()
              && terrainMaterialFlags == environmentLighting.flags()
              && terrainMaterialShadows == (environment.shadowMap != null);
    }

    /** An edit's rebuild is complete but some program its new materials need still links: it waits to commit. */
    private boolean awaitingLinks() {
        return rebuild != null && rebuild.plan != null && rebuild.remaining.isEmpty() && !familySession && !materialsReady();
    }

    boolean busy() {
        return requested != null || rebuild != null || detailJob != null || !pending.isEmpty()
              || coverScene != null && !materialsReady();
    }

    /** The loading stage (ready, buildProgress) waits for a session's family programs; busy does not, nor tests. */
    private void startFamilySession() { familySession = true; }

    private boolean familiesPending() { return familySession && familyPrograms.pending() > 0; }

    /** Family programs build after the session's chunks and generic programs (refine). */
    private boolean buildingFamilies() {
        return familySession && requested == null && rebuild == null && materialsReady();
    }

    /**
     * The installed terrain shows {@code scene}'s board in the wanted graphics: a switch either way shows the loading
     * stage. Programs that only an edit's or a closer view's new materials need link while the board shows: the edit
     * commits once they are linked (awaitingLinks), a closer view's draws wait for them (GpuShaderProvider).
     */
    boolean ready(BoardScene scene) {
        return (materialsReady() || !familySession && sameMaterialConfiguration()) && coverScene != null
              && sameBoard(coverScene, scene) && installedColumns == columns && atlasColumns == columns
              && installedTessellation == tessellation && !familiesPending()
              && (materialCache.prewarmed() || !materialsReady());
    }

    /** The models that could not load and draw as marked boxes, not yet reported, each with why (GpuAssets#takeMissing). */
    Map<String, String> takeMissingAssets() { return assets.takeMissing(); }

    int buildProgress() {
        if (requested == null) { return materialsReady() && !familiesPending() ? -1 : 100; }
        if (rebuild == null || rebuild.request != requested || rebuild.plan == null) { return 0; }
        return 100 * rebuild.replacements.size() / Math.max(1, rebuild.remaining.size() + rebuild.replacements.size());
    }

    /**
     * The loading stage's shader bar: the programs linked of those this load needs so far. The generic programs and
     * their depth twins come from the warm-up's last poll; once the family programs build, their ready and pending
     * counts are added (pending includes one the provider already holds, adopted at once). Counting them only then
     * keeps the bar from standing still through the chunk build, when they may all load from the cache.
     */
    TerrainLoadProgress.Shaders shaderProgress() {
        boolean families = buildingFamilies();
        int ready = families ? familyPrograms.ready().size() : 0;
        int pending = families ? familyPrograms.pending() : 0;
        return new TerrainLoadProgress.Shaders(terrainProgramsLinked + ready, terrainPrograms + ready + pending);
    }

    /** Read on the GL thread; each worker contributes one coherent, non-authoritative progress snapshot. */
    List<TerrainLoadProgress.Status> buildDetails() {
        if (requested == null || rebuild == null || rebuild.request.generation() != requested.generation()) {
            return List.of();
        }
        List<DetailJob> jobs = new ArrayList<>(pending);
        if (detailJob != null && detailJob.rebuilding()) { jobs.add(detailJob); }
        if (jobs.isEmpty()) { return List.of(new TerrainLoadProgress.Status(0, 0, rebuild.progress.snapshot())); }
        BoardScene scene = rebuild.request.scene();
        int columns = (scene.width() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        int rows = (scene.height() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        return jobs.stream().filter(job -> job.generation() == requested.generation())
              .map(job -> new TerrainLoadProgress.Status(TerrainLoadProgress.displayIndex(job.index(), columns, rows) + 1,
                    columns * rows, job.progress().snapshot()))
              .sorted(Comparator.comparingInt(TerrainLoadProgress.Status::section)).toList();
    }

    /** Presentation only: derive cell colors from the current request's existing chunk ownership. */
    TerrainLoadProgress.Sections buildSections() {
        BoardScene scene = requested == null ? coverScene : requested.scene();
        if (scene == null) { return TerrainLoadProgress.Sections.EMPTY; }
        int columns = (scene.width() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        int rows = (scene.height() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        boolean current = requested != null && rebuild != null
              && rebuild.request.generation() == requested.generation() && rebuild.plan != null;
        List<TerrainLoadProgress.SectionState> states = new ArrayList<>(columns * rows);
        for (int index = 0; index < columns * rows; index++) {
            var state = TerrainLoadProgress.SectionState.WAITING;
            if (requested == null || current && !rebuild.remaining.contains(index)) {
                state = TerrainLoadProgress.SectionState.READY;
            } else if (current && rebuild.started.contains(index)) {
                state = TerrainLoadProgress.SectionState.LOADING;
            }
            states.add(state);
        }
        return new TerrainLoadProgress.Sections(columns, rows, states);
    }

    /** A different board must not remain selectable while its replacement is being prepared. */
    void boardChanged() {
        surfaceScars.dispose();
        groundDamage.dispose();
        coverScene = null;
        lunarSource = null;
        lunarTiles = null;
        scatterSource = null;
        scatterTiles = null;
        requested = null;
        meshGeneration++;
    }

    /** Only terrain presentation waits for publication; units, commands and the source's game state remain current. */
    BoardScene presentation(BoardScene scene) {
        return ready(scene) ? scene.withTiles(tiles) : scene;
    }

    TerrainSettings settings() { return installedSettings; }

    GpuGroundDamage groundDamage() { return groundDamage; }
    GpuSurfaceScars surfaceScars() { return surfaceScars; }
    static boolean sculpted(Material material) { return material.has(Sculpt.TYPE); }

    /** Static opaque sculpt scatter can share a page; exclude dynamic masks, ice and liquid additions. */
    static boolean batchableSculpt(Material material) {
        return material.getMask() == (TextureAttribute.Diffuse | IntAttribute.CullFace | Sculpt.TYPE
              | Ground.TYPE | SculptTiles.TYPE | SculptLayers.TYPE);
    }

    /** A nuclear blast's scorch over its blast area (GpuGroundDamage.scorch). */
    void scorch(BoardScene scene, GpuGroundDamage.Scorch scorch) {
        groundDamage.update(scene);
        groundDamage.scorch(scorch);
    }

    void impact(BoardScene scene, GpuGroundDamage.Impact impact) {
        groundDamage.update(scene);
        surfaceScars.update(scene);
        groundDamage.impact(scene, impact, this::tacticalSurface, surfaceScars::add);
    }

    /** Test hook: the ground material cache on or off (GpuMaterialCache), with its state for reports. */
    void setMaterialCache(boolean enabled) { materialCache.setEnabled(enabled); }

    boolean materialCacheComplete() { return materialCache.complete(); }

    String materialCacheReport() { return materialCache.report(); }

    /**
     * Whether combat scars show. On before the board builds (games), it also prepares the plain programs of the objects
     * drawn instanced, with which their scar patches draw (GpuSurfaceScars); a patch on the terrain draws with its
     * receiver's sculpt program. Off where combat cannot happen (the editor, previews).
     */
    void setCombatScars(boolean scars) { combatScars = scars; }

    /** Before the board builds: whether the editor changes it (editable), when it also prepares the palette's kinds. */
    void setEditable(boolean editing) {
        editable = editing;
        kindsPending = editing;
    }

    private static boolean sameBoard(BoardScene a, BoardScene b) {
        return a.boardId() == b.boardId() && a.width() == b.width() && a.height() == b.height();
    }

    private static boolean sameSettings(TerrainSettings a, TerrainSettings b) {
        if (a == null) { return false; }
        BoardGeometry.Tuning x = a.geometry(), y = b.geometry();
        return x.hexScale() == y.hexScale() && x.levelHeight() == y.levelHeight() && x.gridShade() == y.gridShade()
              && x.transitions() == y.transitions() && x.padding() == y.padding()
              && a.terrainRevision() == b.terrainRevision();
    }

    private void checkBuild(long generation) {
        if (meshGeneration != generation || Thread.currentThread().isInterrupted()) { throw new CancellationException(); }
    }

    private void checkBuild(long generation, AtomicBoolean stale) {
        if (stale.get()) { throw new CancellationException(); }
        checkBuild(generation);
    }

    private UpdatePlan prepareUpdate(Request request, BoardScene beforeScene, float previousFloor,
          BoardConcrete previousCoast, Map<Coords, BoardFlow.Current> previousCurrents,
          Map<GroundSlot, BoardScene.Pixels> previousColors,
          Map<DecalSlot, BoardScene.Pixels> previousDecals, boolean changedTuning, boolean changedLimbScale,
          BoardScene.Pixels incline, BoardScene.Pixels highIncline,
          TerrainLoadProgress progress, Map<Coords, List<BoardDecals.Stamp>> previousPaint) {
        BoardScene scene = request.scene();
        progress.begin("layout", 1);
        long generation = request.generation();
        Map<GroundSlot, BoardScene.Pixels> terrainPixels = new ConcurrentHashMap<>(scene.tiles().size() * 2);
        Map<DecalSlot, BoardScene.Pixels> decalPixels = new ConcurrentHashMap<>(scene.tiles().size() * 2);
        float nextFloor = BoardGeometry.floor(scene);
        boolean rebuildAll = changedTuning || beforeScene == null || !sameBoard(beforeScene, scene);
        // The recent road masks' keys hold their roads' tiles, not the settings a full rebuild may bring.
        if (rebuildAll) { roadMaskData.forget(); }
        boolean changedFlow = rebuildAll;
        Map<Coords, BoardFlow.Current> nextCurrents = previousCurrents;
        // The fitted concrete outline reads ground, water, levels, roads and features. An edit that changes none
        // of them keeps the previous fit, and the corner comparison below then has nothing to find.
        boolean refit = rebuildAll || previousCoast == null || concreteInputsChanged(beforeScene, scene);
        BoardConcrete nextCoast = refit ? BoardConcrete.of(scene) : BoardConcrete.adopt(scene, previousCoast);
        // A road entering concrete reads its fitted corners, which hexes farther out than its mask's key can move.
        if (refit && previousCoast != null
              && scene.tiles().stream().anyMatch(tile -> !nextCoast.sameCorners(previousCoast, tile.coords()))) {
            roadMaskData.forget();
        }
        progress.advance();
        progress.begin("changes", scene.tiles().size());
        Set<Coords> changedTiles = new HashSet<>();
        Map<Coords, List<BoardDecals.Stamp>> paint = rebuildAll ? BoardDecals.index(scene) : BoardDecals.update(beforeScene, scene, previousPaint);
        Set<Coords> changedPaint = BoardDecals.changed(previousPaint, paint);
        for (int index = 0; index < scene.tiles().size(); index++) {
            checkBuild(generation);
            BoardScene.Tile tile = scene.tiles().get(index);
            if (!rebuildAll) {
                // The visual floor closes the outside of the board; interior hexes never reach it.
                Coords at = tile.coords();
                if (nextFloor != previousFloor && (at.getX() == 0 || at.getY() == 0
                      || at.getX() == scene.width() - 1 || at.getY() == scene.height() - 1)) { changedTiles.add(at); }
                if (changedLimbScale && hasLimb(tile)) {
                    changedTiles.add(tile.coords());
                }
                BoardScene.Tile before = beforeScene.tiles().get(index);
                // A capture keeps the tile instance of every hex it did not touch; only its fitted concrete
                // corners, which a neighbour's edit can move, still need checking.
                boolean same = before == tile;
                if (!same && (before.blackIce() != tile.blackIce() || before.bare() != tile.bare()
                      || before.barren() != tile.barren() || before.deckMud() != tile.deckMud()
                      || !before.corridor().equals(tile.corridor()) || !before.deckCorridor().equals(tile.deckCorridor())
                      || before.ultraSublevel() != tile.ultraSublevel()
                      || !before.appearance().equals(tile.appearance()))) { changedTiles.add(tile.coords()); }
                // Artwork is recomposed for dirtied chunks only, so a hex whose decal or tileset artwork changed is
                // dirtied here; the atlas comparison that used to notice it now sees just those chunks.
                if (!same && (!Objects.equals(BoardDecals.legacyPaint(before), BoardDecals.legacyPaint(tile))
                      || request.columns() && !sameColumnArt(before, tile))) { changedTiles.add(tile.coords()); }
                // Currents run between liquid hexes only (BoardFlow); dry ground changes level without moving them.
                changedFlow |= !same && (!before.coords().equals(tile.coords()) || !before.liquid().equals(tile.liquid())
                      || tile.liquid().present() && (before.elevation() != tile.elevation() || before.frozen() != tile.frozen()));
                // A dome is fitted to its whole building: a change to one of its hexes refits every hex of it.
                if (!same && before.coords().equals(tile.coords())) {
                    changedTiles.addAll(BoardMultiHexBuilding.domed(beforeScene, before));
                    changedTiles.addAll(BoardMultiHexBuilding.domed(scene, tile));
                    // A neighbour's dome may name this hex as a collapsed one, or take it in now.
                    for (int direction = 0; direction < 6; direction++) {
                        var next = beforeScene.neighbor(before, direction);
                        if (next != null) {
                            changedTiles.addAll(BoardMultiHexBuilding.domed(beforeScene, next));
                            changedTiles.addAll(BoardMultiHexBuilding.domed(scene, scene.tile(next.coords())));
                        }
                    }
                }
                if (!same && !before.coords().equals(tile.coords())) {
                    rebuildAll = true;
                } else {
                    // A water shore reaches the banks and corners it shapes from further out (BoardSurface.Key);
                    // joined landforms also depend on the neighbours' road approaches, two hexes out.
                    boolean shore = !same && (before.elevation() != tile.elevation() || before.waterDepth() != tile.waterDepth()
                          || !before.liquid().equals(tile.liquid()) || before.wayExits() != tile.wayExits()
                          || before.road() != tile.road()
                          || before.cliffTopExits() != tile.cliffTopExits() || before.ultraSublevel() != tile.ultraSublevel()
                          || before.surface() != tile.surface() || before.detailedGround() != tile.detailedGround()
                          || before.backdrop() != tile.backdrop())
                          || refit && !nextCoast.sameCorners(previousCoast, tile.coords());
                    if (shore || !same && (before.frozen() != tile.frozen() || before.biome() != tile.biome()
                          || !before.groundCover().equals(tile.groundCover())
                          || BoardWall.family(before) != BoardWall.family(tile)
                          // A structure's exits pick its own and its neighbours' connection pieces (ConnectedKit#reach).
                          || before.cover().exits() != tile.cover().exits()
                          || !solidFeatures(before).equals(solidFeatures(tile)))) {
                        int reach = shore ? editReach(beforeScene, scene, at) : 2;
                        for (int x = at.getX() - reach; x <= at.getX() + reach; x++) {
                            for (int y = at.getY() - reach - 1; y <= at.getY() + reach + 1; y++) {
                                if (at.distance(x, y) <= reach && scene.tile(new Coords(x, y)) != null) {
                                    changedTiles.add(new Coords(x, y));
                                }
                            }
                        }
                    }
                }
            }
            progress.advance();
        }
        if (changedFlow) {
            progress.begin("currents", 1);
            Map<Coords, BoardFlow.Current> next = GpuWaterShader.surfaceCurrents(scene, BoardFlow.calculate(scene));
            // An outlet edit can reverse a distant flat reach, beyond the edited hex's immediate neighbors.
            Set<Coords> affected = new HashSet<>(previousCurrents.keySet());
            affected.addAll(next.keySet());
            for (Coords coords : affected) {
                if (!Objects.equals(previousCurrents.get(coords), next.get(coords))) {
                    changedTiles.add(coords);
                    // The water field blends each current into the neighbouring hexes, possibly across a chunk border.
                    for (int direction = 0; direction < 6; direction++) {
                        Coords neighbor = coords.translated(direction);
                        if (scene.tile(neighbor) != null) { changedTiles.add(neighbor); }
                    }
                }
            }
            nextCurrents = next;
            progress.advance();
        }
        if (!rebuildAll) {
            // An approach edit can change a span's material or natural form beyond the usual terrain edit radius.
            for (var tile : scene.tiles()) {
                var before = beforeScene.tile(tile.coords());
                if (BoardBridge.feature(tile) == null && BoardBridge.feature(before) == null) { continue; }
                var deck = BoardBridge.deck(scene, tile);
                var oldDeck = BoardBridge.deck(beforeScene, before);
                boolean capArtChanged = request.columns() && !Objects.equals(
                      GpuTilesetTerrain.naturalBridgeArt(scene, tile, deck),
                      GpuTilesetTerrain.naturalBridgeArt(beforeScene, before, oldDeck));
                if (!Objects.equals(deck, oldDeck) || capArtChanged) {
                    changedTiles.add(tile.coords());
                    for (int d = 0; d < 6; d++) {
                        var neighbor = tile.coords().translated(d);
                        if (scene.tile(neighbor) != null) { changedTiles.add(neighbor); }
                    }
                }
            }
        }
        Set<Coords> textureChunks = new HashSet<>();
        changedTiles.forEach(coords -> dirtyChunk(textureChunks, coords));
        Set<Coords> paintOnly = new HashSet<>(changedPaint); paintOnly.removeAll(changedTiles);
        changedTiles.addAll(changedPaint);
        Set<Coords> changedChunks = new HashSet<>();
        changedTiles.forEach(coords -> dirtyChunk(changedChunks, coords));
        boolean all = rebuildAll;
        // The hexes of untouched chunks keep every artwork slot of the previous build, which captured the same
        // pixel objects for them; an edit recomposes only the chunks it dirtied.
        List<BoardScene.Tile> retextured;
        if (all) {
            retextured = scene.tiles();
        } else {
            terrainPixels.putAll(previousColors);
            decalPixels.putAll(previousDecals);
            retextured = new ArrayList<>();
            for (Coords chunk : textureChunks) {
                for (int x = chunk.getX() * CHUNK_SIZE; x < Math.min(scene.width(), (chunk.getX() + 1) * CHUNK_SIZE); x++) {
                    for (int y = chunk.getY() * CHUNK_SIZE; y < Math.min(scene.height(), (chunk.getY() + 1) * CHUNK_SIZE); y++) {
                        retextured.add(scene.tile(x, y));
                    }
                }
            }
        }
        progress.begin("textures", retextured.size());
        retextured.parallelStream().forEach(tile -> request.settings().run(() -> {
            checkBuild(generation);
            if (request.columns()) {
                columnTextures(scene, tile, incline, highIncline, terrainPixels, decalPixels);
                progress.advance();
                return;
            }
            // Enhanced draws its own ground materials and paints the legacy paint only; the tileset art is Classic's.
            DecalSlot slot = new DecalSlot(tile.coords(), 0);
            if (BoardDecals.legacyPaint(tile) != null) { decalPixels.put(slot, BoardDecals.legacyPaint(tile)); }
            else { decalPixels.remove(slot); }
            progress.advance();
        }));
        // A full build names every unrepresented hex once; an edit only the hexes it made so.
        if (!request.columns()) {
            warnUnrepresented(all ? retextured : retextured.stream().filter(tile -> {
                BoardScene.Tile before = beforeScene.tile(tile.coords());
                return before == null || before.detailedGround();
            }).toList());
        }
        // A full build retains only the rim compositions it used; an edit keeps those of the hexes it left alone.
        if (all) { rims.retainUsed(); }
        progress.begin("atlases", 2);
        return new UpdatePlan(nextFloor, nextCoast, nextCurrents, rebuildAll, changedChunks, changedTiles, paintOnly,
              terrainPixels, decalPixels, paint);

    }

    /**
     * A Classic hex's artwork, composed on a terrain worker: its column art with the rims of its drops
     * in its base slot, which a water neighbour's bank borrows, and its tileset decals and object sprites.
     */
    private void columnTextures(BoardScene scene, BoardScene.Tile tile, BoardScene.Pixels incline,
          BoardScene.Pixels highIncline, Map<GroundSlot, BoardScene.Pixels> colors, Map<DecalSlot, BoardScene.Pixels> decals) {
        BoardScene.Pixels art = tile.tileset();
        GroundSlot base = new GroundSlot(tile.coords(), false);
        if (art != null) { colors.put(base, rims.column(scene, tile, art, incline, highIncline)); } else { colors.remove(base); }
        GroundSlot cap = new GroundSlot(tile.coords(), true);
        BoardScene.Pixels capArt = GpuTilesetTerrain.naturalBridgeArt(scene, tile, BoardBridge.deck(scene, tile));
        if (capArt != null) { colors.put(cap, capArt); } else { colors.remove(cap); }
        BoardScene.Pixels[] overlays = { null, tile.tilesetDecals(), tile.tilesetScenery() };
        for (int kind = 1; kind < overlays.length; kind++) {
            DecalSlot slot = new DecalSlot(tile.coords(), kind);
            if (overlays[kind] != null) { decals.put(slot, overlays[kind]); } else { decals.remove(slot); }
        }
    }

    /**
     * Enhanced has its own look for every marking a hex holds; a hex with markings it cannot represent
     * ({@link BoardScene.Tile#detailedGround}) shows its surface's default ground, and the build names it.
     */
    private static void warnUnrepresented(List<BoardScene.Tile> tiles) {
        List<String> hexes = tiles.stream().filter(tile -> !tile.detailedGround())
              .map(tile -> tile.coords().getBoardNum()).toList();
        if (!hexes.isEmpty()) {
            LOGGER.warn("Enhanced has no look for the markings of {} hexes ({}); they show their surface's ground",
                  hexes.size(), String.join(", ", hexes.subList(0, Math.min(8, hexes.size()))));
        }
    }

    /** The art only a Classic build draws: the tileset art, its decals and object sprites. */
    private static boolean sameColumnArt(BoardScene.Tile a, BoardScene.Tile b) {
        return Objects.equals(a.tileset(), b.tileset()) && Objects.equals(a.tilesetDecals(), b.tilesetDecals())
              && Objects.equals(a.tilesetScenery(), b.tilesetScenery());
    }

    /** Authored paint does not change the solid terrain or its neighbour dependencies. */
    private static List<BoardScene.Feature> solidFeatures(BoardScene.Tile tile) {
        return tile.features().stream().filter(f -> f.decoration() == null || !f.decoration().kind().equals("decal")).toList();
    }

    /** Whether any hex changed what the concrete fit reads: its ground, water, level, roads, structures or Edges. */
    private static boolean concreteInputsChanged(BoardScene before, BoardScene after) {
        for (int index = 0; index < after.tiles().size(); index++) {
            BoardScene.Tile was = before.tiles().get(index), is = after.tiles().get(index);
            if (was == is) { continue; }
            if (was.surface() != is.surface() || !was.liquid().equals(is.liquid()) || was.elevation() != is.elevation()
                  || was.waterDepth() != is.waterDepth() || was.frozen() != is.frozen()
                  || was.ultraSublevel() != is.ultraSublevel() || was.bare() != is.bare()
                  || was.backdrop() != is.backdrop()
                  || was.wayExits() != is.wayExits() || was.road() != is.road() || !was.corridor().equals(is.corridor())
                  || was.cliffTopExits() != is.cliffTopExits() || was.detailedGround() != is.detailedGround()
                  || !structures(was).equals(structures(is))
                  || BoardConcrete.edges(was) != BoardConcrete.edges(is)) { return true; }
        }
        return false;
    }

    /**
     * The features the concrete fit reads ({@link BoardConcrete#fitFeature}, the one predicate): structures and placed
     * objects, whose footprint decides where they keep their hex; never trees, rocks or scenery rows.
     */
    private static List<BoardScene.Feature> structures(BoardScene.Tile tile) {
        return tile.features().stream().filter(BoardConcrete::fitFeature).toList();
    }

    /** The installed board's placed objects inside a wall of a Fitted pavement hex ({@link BoardConcrete#cuts}). */
    List<BoardConcrete.Cut> concreteCuts() { return coast == null ? List.of() : coast.cuts(); }

    private static int editReach(BoardScene before, BoardScene after, Coords at) {
        if (!shoreNearby(before, after, at)) { return 2; }
        BoardScene.Tile was = before.tile(at), is = after.tile(at);
        if ((was.liquid().present() || is.liquid().present()) && !deepened(was, is)) { return BoardSurface.SHORE_RINGS; }
        // A height edit can change a neighbouring road approach, which in turn pins its shore field.
        List<Coords> local = new ArrayList<>();
        local.add(at);
        for (int direction = 0; direction < 6; direction++) { local.add(at.translated(direction)); }
        for (Coords coords : local) {
            if (after.tile(coords) != null && BoardRelief.shoreClass(before, before.tile(coords))
                  != BoardRelief.shoreClass(after, after.tile(coords))) { return BoardSurface.SHORE_RINGS; }
        }
        // Local levels/materials affect corners, the adjacent land's corner allowance, and its borrowed walls.
        return 3;
    }

    /**
     * A pool that only grew deeper or shallower and stays at least a level deep. Beyond a level the shore field and the
     * river's channels read no depth ({@link BoardRiver} clamps it), so its bed, the beds it meets and the steps down
     * into it change, as with a level edit beside water.
     */
    private static boolean deepened(BoardScene.Tile was, BoardScene.Tile is) {
        return was.waterDepth() != is.waterDepth() && Math.min(was.waterDepth(), is.waterDepth()) >= 1
              && BoardSurface.shape(was, 0).equals(BoardSurface.shape(is, 0));
    }

    /** Dry relief has only local neighbours; the larger dependency radius belongs to shore deformation. */
    private static boolean shoreNearby(BoardScene before, BoardScene after, Coords at) {
        int radius = BoardSurface.SHORE_RINGS;
        for (int x = Math.max(0, at.getX() - radius); x <= Math.min(after.width() - 1, at.getX() + radius); x++) {
            for (int y = Math.max(0, at.getY() - radius - 1); y <= Math.min(after.height() - 1, at.getY() + radius + 1); y++) {
                if (at.distance(x, y) > radius) { continue; }
                Coords coords = new Coords(x, y);
                if (before.tile(coords).liquid().present() || after.tile(coords).liquid().present()) { return true; }
            }
        }
        return false;
    }

    private static void dirtyChunk(Set<Coords> chunks, Coords coords) {
        chunks.add(new Coords(coords.getX() / CHUNK_SIZE, coords.getY() / CHUNK_SIZE));
    }

    private static TerrainLod initialDetail(BoardScene scene, int x, int y, Camera camera) {
        if (camera == null) { return TerrainLod.FULL; }
        BoundingBox bounds = new BoundingBox().inf();
        for (int cx = x; cx < Math.min(scene.width(), x + CHUNK_SIZE); cx++) {
            for (int cy = y; cy < Math.min(scene.height(), y + CHUNK_SIZE); cy++) {
                BoardScene.Tile tile = scene.tile(new Coords(cx, cy));
                Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
                bounds.ext(center.x - BoardGeometry.width(), center.y - BoardGeometry.height(),
                      center.z - BoardGeometry.level());
                bounds.ext(center.x + BoardGeometry.width(), center.y + BoardGeometry.height(),
                      center.z + BoardRelief.headroom(tile));
            }
        }
        return TerrainLod.select(hexPixels(camera, bounds), null);
    }

    private static float hexPixels(Camera camera, BoundingBox bounds) {
        if (camera.projection.val[Matrix4.M33] != 0) { return BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera); }
        // Nearest possible depth: every point of a perspective chunk receives at least the required detail.
        Vector3 nearest = new Vector3(camera.direction.x >= 0 ? bounds.min.x : bounds.max.x,
              camera.direction.y >= 0 ? bounds.min.y : bounds.max.y,
              camera.direction.z >= 0 ? bounds.min.z : bounds.max.z);
        return BoardGeometry.width() * BoardCamera.pixelsPerUnit(camera, nearest);
    }

    /** A bounded edit pipeline shares two CPU workers; GL collection/upload remains under the frame budget. */
    boolean refine(Camera camera) {
        return refine(camera, false);
    }

    boolean refine(Camera camera, boolean editing) {
        if (kinds != null) { takeKinds(); }
        // A requested board's update plan starts before links, which can hold a first frame for seconds; links wait
        // until its first sections start, so these prepare on the workers while they link.
        if (requested != null && rebuild == null && detailJob == null && pending.isEmpty()) { startRebuild(); }
        boolean starting = rebuild != null && rebuild.started.isEmpty()
              && (rebuild.plan == null || !rebuild.remaining.isEmpty());
        // Launch links while CPU terrain jobs are being prepared, and poll without blocking the loading screen.
        if (!materialsReady() && linking && !starting) {
            if (terrainMaterialPreparation == null && requested != null) { updateLight(requested.scene().light()); }
            prepareTerrainMaterials();
        }
        // Family programs build only behind the loading stage, after the generic ones: on Iris Xe a link built beside
        // interactive frames once held one frame for 9 s (GpuFamilyPrograms).
        if (familyPrograms.configure(environment)) {
            terrainBatch.clear();
            startFamilySession();
        }
        if (buildingFamilies()) {
            if (familyPrograms.prepare(terrainMaterialPreparation, batch.getShaderProvider(), environment)) {
                terrainBatch.clear();
            }
            familySession = familyPrograms.pending() > 0;
        }
        // The ground material cache's first view builds behind the loading stage (ready), once the board and every
        // program are in.
        if (requested == null && rebuild == null && materialsReady()) {
            // Its fill reads the fields' biomes (cachedField).
            biomes.update(coverScene);
            materialCache.prewarm(camera, !installedColumns, !fluffHidden, this::drawCacheTops,
                  () -> cacheChunks(camera));
        }
        // While opening/replacing a board or switching the graphics (both behind the loading stage), use more of the
        // loading frame for uploads instead of stretching each chunk's handoffs over several frames. An editor change
        // gets a larger bounded slice so each small edit does not wait dozens of frames (its collection and upload take
        // about 60 ms of render-thread time, a handful of frames at this slice); ordinary camera refinement keeps the
        // two-millisecond budget.
        long deadline = System.nanoTime() + (coverScene == null ? 8_000_000
              : editing && (requested != null || rebuild != null) ? 12_000_000
              : installedColumns != columns || atlasColumns != columns ? 8_000_000 : 2_000_000);
        drainRetired(deadline);
        // A settled board still needs the camera scan in advanceBuild to start a new detail job after zooming.
        if (camera == null && requested == null && rebuild == null && detailJob == null && pending.isEmpty()) {
            return false;
        }
        do {
            if (advanceBuild(camera, deadline)) { return true; }
            // While one chunk's meshes prepare on a worker, another chunk that is ready takes its render-thread turn in
            // this frame (advanceBuild swaps it in), so a rebuild's chunks build side by side, not one a frame.
            if (detailJob != null && !detailJob.ready() && pending.stream().noneMatch(DetailJob::ready)
                  || rebuild != null && !rebuild.preparation.isDone() || !busy() || awaitingLinks()) { break; }
        } while (System.nanoTime() < deadline);
        return false;
    }

    /**
     * Drives the private renderer of an editor board's palette kinds; once it installed them, their materials join this
     * terrain's warm-up and it goes. It links nothing itself (linking): this terrain links their programs.
     */
    private void takeKinds() {
        kinds.refine(null);
        if (kinds.coverScene == null || kinds.requested != null || kinds.rebuild != null) { return; }
        if (!kindScenes.isEmpty()) {
            kinds.update(kindScenes.removeFirst(), null);
            return;
        }
        for (Renderable sample : kinds.terrainMaterialSamples) {
            prepareTerrainMaterial(sample.material, sample.meshPart.mesh);
            Material swapped = GpuIceShader.landOrBorder(sample.material);
            if (swapped != null) { prepareTerrainMaterial(swapped, sample.meshPart.mesh); }
        }
        biomeVegetation.prepareMaterials(this::prepareTerrainMaterial);
        kinds.dispose();
        kinds = null;
        kindsPending = false;
    }

    private boolean advanceBuild(Camera camera, long deadline) {
        // A level the replaced-chunk cache holds installs before a finished build can evict it.
        if (installCached(camera)) { return true; }
        if (!pending.isEmpty() && (detailJob == null || !detailJob.ready())) {
            int ready = 0;
            for (int i = 0; i < pending.size(); i++) {
                if (pending.get(i).ready()) { ready = i; break; }
            }
            DetailJob next = pending.remove(ready);
            if (detailJob != null) { pending.add(detailJob); }
            detailJob = next;
        }
        if (detailJob != null && detailJob.ready()) {
            DetailJob job = detailJob;
            detailJob = null;
            boolean valid = job.generation() == meshGeneration && !job.stale().get();
            if (!job.rebuilding()) {
                valid &= requested == null && job.source() == chunks.get(job.index())
                      && (camera == null || job.settings().call(
                            () -> TerrainLod.select(hexPixels(camera, job.source().bounds), job.source().lod)) == job.lod());
            }
            if (valid) {
                try (TerrainSettings.Scope ignored = TerrainSettings.use(job.settings())) {
                    if (job.build() == null) {
                        int rows = (job.scene().height() + CHUNK_SIZE - 1) / CHUNK_SIZE;
                        // A rebuild's chunks are for its request's switch; camera refinement keeps the installed one.
                        boolean tessellated = job.rebuilding() && rebuild != null ? rebuild.request.tessellated()
                              : installedTessellation;
                        ChunkBuild build = new ChunkBuild(job.scene(), job.index() / rows * CHUNK_SIZE,
                              job.index() % rows * CHUNK_SIZE, job.floor(), job.lod(), job.columns(), tessellated,
                              job.surfaces().join(), job.source(), job.progress());
                        detailJob = new DetailJob(job.generation(), job.index(), job.source(), job.lod(), job.scene(),
                              job.settings(), job.floor(), job.rebuilding(), job.columns(), null, build, null, job.progress(),
                              job.stale());
                    } else if (job.meshes() == null) {
                        detailJob = job;
                        if (job.build().collectUntil(deadline)) {
                            detailJob = new DetailJob(job.generation(), job.index(), job.source(), job.lod(), job.scene(),
                                  job.settings(), job.floor(), job.rebuilding(), job.columns(), null, job.build(),
                                  CompletableFuture.runAsync(() -> job.settings().run(() ->
                                        job.build().prepare(() -> checkBuild(job.generation(), job.stale()))), detailWorker),
                                  job.progress(), job.stale());
                        }
                    } else {
                        job.meshes().join();
                        if (!job.build().uploadUntil(deadline)) { detailJob = job; return false; }
                        Chunk next = job.build().finish();
                        if (job.rebuilding()) {
                            rebuild.replacements.put(job.index(), next);
                            rebuild.remaining.remove(job.index());
                            rebuild.progress.advance();
                            if (rebuild.remaining.isEmpty()) { rebuild.progress.begin("finishing", 1); }
                        } else {
                            replaceDetail(job.index(), next);
                            return true;
                        }
                    }
                }
            } else if (job.build() != null) {
                retiredChunks.add(job.build().chunk);
            }
        }
        if (detailJob != null) {
            if (rebuild != null && rebuild.plan != null && rebuild.request.generation() == meshGeneration) { queueChunks(camera); }
            return refineChunks(camera);
        }
        if (!pending.isEmpty()) {
            detailJob = pending.removeFirst();
            return refineChunks(camera);
        }
        if (rebuild != null && rebuild.request.generation() != meshGeneration) {
            if (!rebuild.preparation.isDone()) { return false; }
            retiredChunks.addAll(rebuild.replacements.values());
            rims.clear();
            rebuild = null;
        }
        if (requested != null) {
            if (rebuild == null) {
                startRebuild();
                return false;
            }
            if (!rebuild.preparation.isDone()) { return false; }
            try (TerrainSettings.Scope ignored = TerrainSettings.use(rebuild.request.settings())) {
                BoardScene snapshot = rebuild.request.scene();
                if (rebuild.plan == null) {
                    rebuild.plan = rebuild.preparation.join();
                    prepareAtlases(snapshot, rebuild.plan, rebuild.progress);
                    int rows = (snapshot.height() + CHUNK_SIZE - 1) / CHUNK_SIZE;
                    for (int x = 0; x < snapshot.width(); x += CHUNK_SIZE) {
                        for (int y = 0; y < snapshot.height(); y += CHUNK_SIZE) {
                            Coords at = new Coords(x / CHUNK_SIZE, y / CHUNK_SIZE);
                            if (rebuild.plan.all() || rebuild.plan.changed().contains(at) || atlasChanges.contains(at)) {
                                rebuild.remaining.add(at.getX() * rows + at.getY());
                            }
                        }
                    }
                    rebuild.progress.begin("sections", rebuild.remaining.size());
                    return false;
                }
                // An edit shows once every program its new materials need is linked (ready keeps the board shown).
                if (awaitingLinks()) { return false; }
                if (rebuild.remaining.isEmpty()) { commitRebuild(); return true; }
                queueChunks(camera);
                return false;
            }
        }
        return refineChunks(camera);
    }

    /** A chunk the camera wants at another detail: refinements first, the largest first, then the nearest. */
    private record Wanted(int index, TerrainLod lod, float score, float distance) { }

    /**
     * The chunks in the frustum whose detail is not the one their projected size wants, none of them in flight. A camera
     * job whose level a zoom has passed turns stale and frees its chunk for the wanted level.
     */
    private List<Wanted> wanted(Camera camera, Set<Integer> started) {
        List<Wanted> wanted = new ArrayList<>();
        // Classic columns have one level of detail: the camera never refines them. Nor does it during a rebuild.
        if (camera == null || coverScene == null || installedColumns || requested != null || rebuild != null) {
            return wanted;
        }
        Vector3 centre = new Vector3();
        try (TerrainSettings.Scope ignored = TerrainSettings.use(installedSettings)) {
            List<DetailJob> inFlight = new ArrayList<>(pending);
            if (detailJob != null) { inFlight.add(detailJob); }
            for (DetailJob job : inFlight) {
                if (!job.rebuilding() && TerrainLod.select(hexPixels(camera, job.source().bounds), job.source().lod)
                      != job.lod()) {
                    job.stale().set(true);
                }
                if (!job.stale().get()) { started.add(job.index()); }
            }
            for (int i = 0; i < chunks.size(); i++) {
                Chunk chunk = chunks.get(i);
                if (started.contains(i) || !camera.frustum.boundsInFrustum(chunk.bounds)) { continue; }
                float pixels = hexPixels(camera, chunk.bounds);
                TerrainLod lod = TerrainLod.select(pixels, chunk.lod);
                if (lod == chunk.lod) { continue; }
                chunk.bounds.getCenter(centre);
                float dx = centre.x - camera.position.x, dy = centre.y - camera.position.y;
                wanted.add(new Wanted(i, lod, lod.ordinal() < chunk.lod.ordinal() ? pixels : -pixels, dx * dx + dy * dy));
            }
        }
        // An orthographic view gives every chunk one size: those nearest the view start first.
        wanted.sort(Comparator.comparingDouble((Wanted want) -> -want.score()).thenComparingDouble(Wanted::distance));
        return wanted;
    }

    /**
     * Installs one level the replaced-chunk cache holds for a chunk the camera wants (true), whatever is in flight:
     * builds taken first evicted the very levels a zoom back out wanted next.
     */
    private boolean installCached(Camera camera) {
        for (Wanted want : wanted(camera, new HashSet<>())) {
            Chunk cached = detailCache.get(want.index());
            if (cached != null && cached.lod == want.lod()) {
                replaceDetail(want.index(), detailCache.remove(want.index()));
                return true;
            }
        }
        return false;
    }

    /**
     * Camera refinement: the chunks the camera wants at another detail ({@link #wanted}) start while fewer than
     * {@link #REFINE_JOBS} are in flight, after any cached level installs. Each goes straight to its level: medium
     * detail prepares in nearly the time full detail does, so a stop there only delayed the full ground and its grass.
     */
    private boolean refineChunks(Camera camera) {
        if (installCached(camera)) { return true; }
        Set<Integer> started = new HashSet<>();
        List<Wanted> wanted = wanted(camera, started);
        try (TerrainSettings.Scope ignored = TerrainSettings.use(installedSettings)) {
            for (int i = 0; i < wanted.size() && started.size() < REFINE_JOBS; i++) {
                Wanted want = wanted.get(i);
                startChunk(want.index(), chunks.get(want.index()), want.lod(), coverScene, installedSettings, floor,
                      currents, false);
                started.add(want.index());
            }
        }
        return false;
    }

    /** The requested board's update plan, on a worker, against the installed state it changes. */
    private void startRebuild() {
        Request request = requested;
        BoardScene previous = coverScene;
        float bottom = floor;
        BoardConcrete previousCoast = coast;
        Map<Coords, BoardFlow.Current> flow = currents;
        Map<GroundSlot, BoardScene.Pixels> colors = terrainPixels;
        Map<DecalSlot, BoardScene.Pixels> overlays = decalPixels;
        BoardScene.Pixels incline = assets.inclineMask(), highIncline = assets.highInclineMask();
        // A graphics switch rebuilds every chunk, as a new board does; so does one reversed after its atlases.
        boolean changedTuning = installedSettings == null || !sameSettings(installedSettings, request.settings())
              || installedColumns != request.columns() || atlasColumns != request.columns()
              || installedTessellation != request.tessellated();
        boolean changedLimbScale = installedSettings != null
              && installedSettings.geometry().unitScale() != request.settings().geometry().unitScale();
        TerrainLoadProgress progress = new TerrainLoadProgress();
        var previousPaint = installedPaint;
        rebuild = new Rebuild(request, CompletableFuture.supplyAsync(() -> request.settings().call(
              () -> prepareUpdate(request, previous, bottom, previousCoast, flow, colors, overlays,
                    changedTuning, changedLimbScale, incline, highIncline, progress, previousPaint)), detailWorker), progress);
    }

    /** Loading and local edits share the same bounded CPU/GL pipeline, without extra worker threads. */
    private void queueChunks(Camera camera) {
        try (TerrainSettings.Scope ignored = TerrainSettings.use(rebuild.request.settings())) {
            BoardScene snapshot = rebuild.request.scene();
            int columns = (snapshot.width() + CHUNK_SIZE - 1) / CHUNK_SIZE;
            int rows = (snapshot.height() + CHUNK_SIZE - 1) / CHUNK_SIZE;
            boolean loading = !ready(snapshot);
            while (pending.size() + (detailJob == null ? 0 : 1) < BUILD_JOBS) {
                int candidate = -1;
                float priority = -Float.MAX_VALUE;
                for (int index : rebuild.remaining) {
                    if (rebuild.started.contains(index)) { continue; }
                    float x = BoardGeometry.centerX(new Coords(index / rows * CHUNK_SIZE, 0));
                    float y = BoardGeometry.centerY(new Coords(0, index % rows * CHUNK_SIZE));
                    float score = loading ? -TerrainLoadProgress.displayIndex(index, columns, rows)
                          : camera == null ? -index : -camera.position.dst2(x, y, 0);
                    if (!loading && camera != null && camera.frustum.sphereInFrustum(x, y, 0,
                          CHUNK_SIZE * BoardGeometry.width())) { score += 1e20f; }
                    if (score > priority) { priority = score; candidate = index; }
                }
                if (candidate < 0) { break; }
                rebuild.started.add(candidate);
                int x = candidate / rows * CHUNK_SIZE, y = candidate % rows * CHUNK_SIZE;
                TerrainLod lod = rebuild.request.columns() ? TerrainLod.FULL : initialDetail(snapshot, x, y, camera);
                startChunk(candidate, rebuild.plan.all() ? null : chunks.get(candidate), lod, snapshot, rebuild.request.settings(), rebuild.plan.floor(),
                      rebuild.plan.currents(), true);
            }
        }
    }

    private void startChunk(int index, Chunk source, TerrainLod lod, BoardScene scene, TerrainSettings settings,
          float bottom, Map<Coords, BoardFlow.Current> flow, boolean rebuilding) {
        int rows = (scene.height() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        int x = index / rows * CHUNK_SIZE, y = index % rows * CHUNK_SIZE;
        long generation = meshGeneration;
        // Camera refinement never runs on Classic chunks: a job outside a rebuild is an Enhanced one.
        boolean columns = rebuilding && rebuild.request.columns();
        Set<Coords> reuse = new HashSet<>();
        Map<Coords, BoardSurface.WaterGeometry> waterShapes = new HashMap<>();
        Map<Coords, BoardTacticalGeometry.Surface> paintSurfaces = new HashMap<>();
        // A Classic paint edit rebuilds the changed hexes' columns, which are cheap, and plants nothing.
        boolean onlyPaint = !columns && rebuilding && source != null && !rebuild.plan.all()
              && !atlasChanges.contains(new Coords(x / CHUNK_SIZE, y / CHUNK_SIZE))
              && source.tileMeshes.keySet().stream().noneMatch(at -> rebuild.plan.changedTiles().contains(at)
                    && !rebuild.plan.paintOnly().contains(at));
        // Project onto the installed surface; camera refinement can change its detail independently later.
        TerrainLod buildLod = onlyPaint ? source.lod : lod;
        // A level edit that moves the chunk's wall charts (GpuMaterialCache.Charts) rebuilds every hex: a reused one
        // would keep its walls' old places.
        if (rebuilding && source != null && source.lod == buildLod && (onlyPaint
              || settings.call(() -> new GpuMaterialCache.Charts(scene, x, y).sameLayout(source.charts)))) {
            for (Coords coords : source.tileMeshes.keySet()) {
                if (atlasTileChanges.contains(coords)) { continue; }
                if (!rebuild.plan.changedTiles().contains(coords)) { reuse.add(coords); }
                else if (rebuild.plan.paintOnly().contains(coords)) {
                    var retained = source.tileMeshes.get(coords).support;
                    // A shape a query built keeps no ground cover support; replanting needs it.
                    if (retained != null && !source.columns && retained.vegetation() == null
                          && BoardVegetation.covers(scene, scene.tile(coords), source.lod)) { retained = null; }
                    // Borrow exact installed support. Without it the hex takes the normal terrain preparation path.
                    if (retained != null) { paintSurfaces.put(coords, retained); reuse.add(coords); }
                }
            }
        }
        if (source != null) {
            for (Coords coords : reuse) {
                var water = source.tileMeshes.get(coords).water;
                if (water != null) { waterShapes.put(coords, water); }
            }
        }
        TerrainLoadProgress progress = new TerrainLoadProgress();
        var paint = rebuilding ? rebuild.plan.paint() : installedPaint;
        Set<Coords> repainted = onlyPaint ? rebuild.plan.paintOnly() : Set.of();
        AtomicBoolean stale = new AtomicBoolean();
        DetailJob job = new DetailJob(generation, index, source, buildLod, scene, settings, bottom, rebuilding, columns,
              CompletableFuture.supplyAsync(() -> settings.call(
                    () -> columns ? prepareColumns(scene, x, y, bottom, buildLod, flow, settings, reuse, paintSurfaces, paint,
                          () -> checkBuild(generation, stale), progress, roadMaskData)
                          : onlyPaint ? preparePaint(scene, x, y, bottom, buildLod, paintSurfaces, paint, repainted,
                          () -> checkBuild(generation, stale))
                          : prepare(scene, x, y, bottom, buildLod, flow, settings, reuse, waterShapes,
                          () -> checkBuild(generation, stale), progress, roadMaskData, paint, paintSurfaces)), detailWorker),
              null, null, progress, stale);
        // The job in a render-thread stage keeps it; this one waits its turn (advanceBuild).
        if (detailJob == null) { detailJob = job; } else { pending.add(job); }
    }

    /**
     * The CPU side of one Classic chunk: each rebuilt hex's tileset column, which its meshes, picking
     * and draped overlays share, with the board's {@code currents} for its liquid and its {@code paint}. Connected routes
     * reuse the shared grade profiles inside nominal hex columns; surrounding terrain remains flat and sharp.
     */
    static Prepared prepareColumns(BoardScene scene, int startX, int startY, float floor,
          Map<Coords, BoardFlow.Current> currents, Set<Coords> reused, Map<Coords, BoardTacticalGeometry.Surface> retained,
          Map<Coords, List<BoardDecals.Stamp>> paint, Runnable check, TerrainLoadProgress progress) {
        TerrainSettings settings = TerrainSettings.current() == null ? TerrainSettings.capture() : TerrainSettings.current();
        return prepareColumns(scene, startX, startY, floor, TerrainLod.FULL, currents, settings, reused, retained,
              paint, check, progress, new GpuRoads.Masks());
    }

    private static Prepared prepareColumns(BoardScene scene, int startX, int startY, float floor, TerrainLod lod,
          Map<Coords, BoardFlow.Current> currents, TerrainSettings settings, Set<Coords> reused,
          Map<Coords, BoardTacticalGeometry.Surface> retained, Map<Coords, List<BoardDecals.Stamp>> paint,
          Runnable check, TerrainLoadProgress progress, GpuRoads.Masks masks) {
        Map<Coords, BoardTacticalGeometry.Surface> columns = new HashMap<>(retained);
        Map<Coords, BoardSurface> surfaces = new java.util.concurrent.ConcurrentHashMap<>();
        List<BoardScene.Tile> chunkTiles = new ArrayList<>();
        int width = Math.min(scene.width(), startX + CHUNK_SIZE) - startX;
        int height = Math.min(scene.height(), startY + CHUNK_SIZE) - startY;
        for (int x = startX; x < startX + width; x++) {
            for (int y = startY; y < startY + height; y++) {
                check.run();
                Coords coords = new Coords(x, y);
                if (!reused.contains(coords)) { chunkTiles.add(scene.tile(coords)); }
            }
        }
        progress.begin("route supports", chunkTiles.size());
        chunkTiles.parallelStream().forEach(tile -> settings.run(() -> {
            check.run();
            if (GpuTilesetTerrain.infrastructure(scene, tile)) {
                surfaces.put(tile.coords(), BoardSurface.columns(scene, tile, lod));
            }
            progress.advance();
        }));
        progress.begin("columns", chunkTiles.size());
        for (var tile : chunkTiles) {
            check.run();
            columns.put(tile.coords(), GpuTilesetTerrain.column(scene, tile, floor, surfaces.get(tile.coords()), surfaces));
            progress.advance();
        }
        Infrastructure infrastructure = prepareInfrastructure(scene, chunkTiles, lod, settings, surfaces, check, progress, masks, true);
        return new Prepared(surfaces, null, null, columns, Map.of(), Map.of(), currents, infrastructure.roads(),
              infrastructure.bridges(), infrastructure.bridgeShapes(), Map.of(), reused, paint, false);
    }

    /**
     * A paint edit needs receiving triangles only, and the ground cover of the hexes whose paint changed
     * ({@code repainted}), planted again on their support: no plant grows through paint. It neither constructs nor
     * uploads solid terrain.
     */
    private static Prepared preparePaint(BoardScene scene, int x, int y, float floor, TerrainLod lod,
          Map<Coords, BoardTacticalGeometry.Surface> retained, Map<Coords, List<BoardDecals.Stamp>> paint,
          Set<Coords> repainted, Runnable check) {
        Map<Coords, BoardTacticalGeometry.Surface> support = new HashMap<>(retained);
        Map<Coords, BoardSurface> sources = new HashMap<>();
        List<BoardScene.Tile> missing = new ArrayList<>();
        for (int cx = x; cx < Math.min(scene.width(), x + CHUNK_SIZE); cx++) {
            for (int cy = y; cy < Math.min(scene.height(), y + CHUNK_SIZE); cy++) {
                Coords at = new Coords(cx, cy);
                if (!support.containsKey(at) && (repainted.contains(at) || !paint.getOrDefault(at, List.of()).isEmpty())) {
                    check.run();
                    var tile = scene.tile(at);
                    missing.add(tile);
                    sources.put(at, new BoardSurface(scene, tile, lod));
                }
            }
        }
        var plantSources = BoardVegetation.prepare(scene, missing, lod, floor, sources);
        // A repainted hex that now grows nothing keeps a null entry: it replaces the installed plants too.
        Map<Coords, BoardPlants> plants = new HashMap<>();
        for (int cx = x; cx < Math.min(scene.width(), x + CHUNK_SIZE); cx++) {
            for (int cy = y; cy < Math.min(scene.height(), y + CHUNK_SIZE); cy++) {
                check.run(); Coords at = new Coords(cx, cy);
                boolean replant = repainted.contains(at);
                if (replant || !paint.getOrDefault(at, List.of()).isEmpty()) {
                    support.computeIfAbsent(at, key -> BoardTacticalGeometry.Surface.of(plantSources.get(key), scene, floor, plantSources));
                }
                if (replant) {
                    plants.put(at, BoardPlants.plant(scene, scene.tile(at), support.get(at), lod, paint.getOrDefault(at, List.of())));
                }
            }
        }
        return new Prepared(Map.of(), null, null, support, plants, Map.of(), Map.of(), Map.of(),
              Map.of(), Map.of(), Map.of(), Set.of(), paint, true);
    }

    private void prepareAtlases(BoardScene scene, UpdatePlan plan, TerrainLoadProgress progress) {
        ground.retainReplacedPages();
        decals.retainReplacedPages();
        ground.updateRegions(plan.colors()).forEach(slot -> {
            dirtyChunk(atlasChanges, slot.coords());
            atlasTileChanges.add(slot.coords());
            if (!slot.rims()) {
                for (int direction = 0; direction < 6; direction++) {
                    BoardScene.Tile neighbor = scene.tile(slot.coords().translated(direction));
                    if (neighbor != null && neighbor.liquid().present()) {
                        dirtyChunk(atlasChanges, neighbor.coords());
                        atlasTileChanges.add(neighbor.coords());
                    }
                }
            }
        });
        progress.advance();
        decals.updateRegions(plan.decals()).forEach(slot -> {
            dirtyChunk(atlasChanges, slot.coords());
            atlasTileChanges.add(slot.coords());
        });
        progress.advance();
        atlasColumns = rebuild.request.columns();
    }

    private void commitRebuild() {
        scarSurfaceRevision++;
        BoardScene scene = rebuild.request.scene();
        UpdatePlan plan = rebuild.plan;
        // An edit's rebuilt chunks, old and new: the ground material cache refills only what they reach, and their
        // walls' charts.
        BoundingBox rebuilt = new BoundingBox().inf(), rebuiltWalls = new BoundingBox().inf();
        List<Chunk> edited = new ArrayList<>();
        if (plan.all()) {
            propBatch.dispose();
            terrainPages.dispose();
            topPages.dispose();
            paintPages.dispose();
            waterPages.dispose();
            retiredChunks.addAll(chunks);
            chunks.clear();
            int count = ((scene.width() + CHUNK_SIZE - 1) / CHUNK_SIZE) * ((scene.height() + CHUNK_SIZE - 1) / CHUNK_SIZE);
            for (int index = 0; index < count; index++) { chunks.add(rebuild.replacements.get(index)); }
        } else {
            rebuild.replacements.forEach((index, next) -> {
                if (next.paintOnlyUpdate) { installPaint(chunks.get(index), next); retiredChunks.add(next); }
                else {
                    Chunk old = chunks.set(index, next);
                    rebuilt.ext(old.bounds).ext(next.bounds);
                    union(union(rebuiltWalls, old.wallBounds), next.wallBounds);
                    retiredChunks.add(old);
                    if (next.detailTops) { edited.add(next); }
                }
            });
        }
        coverScene = scene;
        tiles = scene.tiles();
        editorPropsInstalled = true;
        lavaLighting.setFungus(chunks.stream().flatMap(chunk -> chunk.fungalLights.stream()).toList());
        installedSettings = rebuild.request.settings();
        if (installedColumns != rebuild.request.columns() && rebuild.request.columns()) {
            // The 3D relief's board-wide layers, once, on a switch to Classic; the ocean follows in animate. The
            // per-hex biome texture stays: Classic's ice reads it.
            groundCover.dispose();
            biomeVegetation.dispose();
        }
        installedColumns = rebuild.request.columns();
        installedTessellation = rebuild.request.tessellated();
        floor = plan.floor();
        coast = plan.coast();
        currents = plan.currents();
        terrainPixels = plan.colors();
        decalPixels = plan.decals();
        installedPaint = plan.paint();
        chunkRows = (scene.height() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        if (requested == rebuild.request) { requested = null; }
        rebuild = null;
        atlasChanges.clear();
        atlasTileChanges.clear();
        ground.publish();
        decals.publish();
        terrainBatch.clear();
        pickingSurfaces.clear();
        detailPixelsPerUnit = Float.NaN;
        // The ground material cache refills what a build changed before the new relief draws, the whole board after a
        // load, which also takes the view's storage.
        materialCache.retireTwins();
        BoundingBox board = new BoundingBox().inf();
        for (Chunk chunk : chunks) { board.ext(chunk.bounds); }
        int since = materialCache.rebuilt(board, plan.all() ? null : rebuilt, BoardRelief.metres(1));
        materialCache.invalidate(rebuiltWalls);
        for (Chunk chunk : edited) { chunk.cacheSince = since; }
        if (plan.all()) {
            materialCache.allocate(cachesGround() && chunks.stream().anyMatch(chunk -> !chunk.topRenderables.isEmpty()),
                  chunks.stream().anyMatch(chunk -> chunk.wallBounds.isValid()));
        }
        hasCutaways = chunks.stream().anyMatch(chunk -> !chunk.cutaways.isEmpty());
        Set<GpuBuilding.Assembly> liveBuildings = new HashSet<>();
        // Generated models the installed chunks draw: industrial plants, and multi-hex building shares and their glass.
        Set<Model> liveIndustrial = new HashSet<>();
        Set<Model> liveRoutes = new HashSet<>();
        for (Chunk chunk : chunks) {
            for (Prop prop : chunk.props) {
                if (prop.decorationFeature != null && BoardRail.route(prop.decorationFeature)) { liveRoutes.add(prop.instance.model); }
            }
            for (Prop prop : chunk.props) { if (prop.building != null) { liveBuildings.add(prop.building); } }
            for (Prop prop : chunk.props) { if (prop.industrial || prop.levels != null) { liveIndustrial.add(prop.instance.model); } }
            for (ModelInstance pane : chunk.glass) { liveIndustrial.add(pane.model); }
        }
        for (Chunk chunk : detailCache.values()) {
            for (Prop prop : chunk.props) {
                if (prop.decorationFeature != null && BoardRail.route(prop.decorationFeature)) { liveRoutes.add(prop.instance.model); }
            }
            for (Prop prop : chunk.props) { if (prop.building != null) { liveBuildings.add(prop.building); } }
            for (Prop prop : chunk.props) { if (prop.industrial || prop.levels != null) { liveIndustrial.add(prop.instance.model); } }
            for (ModelInstance pane : chunk.glass) { liveIndustrial.add(pane.model); }
        }
        Set<Mesh> sharedSources = new HashSet<>();
        for (Chunk chunk : chunks) {
            for (Renderable part : chunk.sharedShadows) { sharedSources.add(part.meshPart.mesh); }
            for (Renderable part : chunk.sharedProps) { sharedSources.add(part.meshPart.mesh); }
            for (Renderable part : chunk.decorationRenderables) { sharedSources.add(part.meshPart.mesh); }
        }
        for (Chunk chunk : detailCache.values()) {
            for (Renderable part : chunk.sharedShadows) { sharedSources.add(part.meshPart.mesh); }
            for (Renderable part : chunk.sharedProps) { sharedSources.add(part.meshPart.mesh); }
            for (Renderable part : chunk.decorationRenderables) { sharedSources.add(part.meshPart.mesh); }
        }
        trees.retainParts(sharedSources);
        assets.retain(this, liveBuildings, liveIndustrial, liveRoutes);
        updateLight(scene.light());
    }

    /**
     * Transfer only the new paint meshes and the repainted hexes' plants (an unchanged hex hands back the same plants,
     * so nothing uploads again); the temporary chunk retires the old meshes through normal disposal.
     */
    private static void installPaint(Chunk target, Chunk replacement) {
        List<ModelInstance> old = new ArrayList<>(target.authoredPaint);
        target.authoredPaint.clear(); target.authoredPaint.addAll(replacement.authoredPaint);
        replacement.authoredPaint.clear(); replacement.authoredPaint.addAll(old);
        target.paintRenderables = null;
        replacement.tileMeshes.forEach((coords, next) -> {
            TileMesh tile = target.tileMeshes.get(coords);
            tile.paint.clear(); tile.paint.addAll(next.paint);
            tile.ranges.removeIf(range -> range.layer() == 6); tile.ranges.addAll(next.ranges);
            tile.plants = next.plants;
        });
    }

    /** Retain one layout per program variant discovered in actual chunk materials. */
    private void prepareTerrainMaterial(Material material, VertexAttributes attributes) {
        prepareTerrainMaterial(material, attributes, material.has(GpuTreeInstances.Instanced.TYPE)
              ? GpuTreeInstances.instanceAttributes(attributes) : null);
    }

    private void prepareTerrainMaterial(Material material, Mesh mesh) {
        prepareTerrainMaterial(material, mesh.getVertexAttributes(), mesh.getInstancedAttributes());
    }

    private void prepareTerrainMaterial(Material material, VertexAttributes attributes, VertexAttributes instances) {
        // Built for tessellation, a sculpted material's curving copy draws once a chunk refines to full detail: its
        // programs link now, behind the loading stage, so a zoom never links one (GpuTessellation.curvedCopy).
        if (tessellation && curvable(material)) {
            prepareTerrainMaterial(GpuTessellation.curvedCopy(material),
                  material.has(GpuRoads.Mask.TYPE) ? GpuRoads.TESSELLATED_VERTICES
                        : GpuTessellation.contacts(attributes), instances);
        }
        for (Renderable sample : terrainMaterialSamples) {
            // A cliff chunk's relief takes its own sample, which links the program's depth twin too.
            if (sample.material.getMask() == material.getMask() && prepass(sample.material) == prepass(material)
                  && GpuTessellation.curved(sample.material) == GpuTessellation.curved(material)
                  && sameMaterialVariant(material, GpuIceShader.mode(sample.material), linearDiffuse(sample.material),
                        GpuSurfaceBlend.ordinaryFamily(sample.material), waterMode(sample.material),
                        flowingMagma(sample.material))
                  && sample.meshPart.mesh.getVertexAttributes().equals(attributes)
                  && Objects.equals(sample.meshPart.mesh.getInstancedAttributes(), instances)) {
                // One sample stands for its programs. A blend the cache draws takes the place of one it cannot (sand),
                // so the variant's cache programs link behind the loading stage too.
                if (!GpuMaterialCache.cacheable(sample.material) && GpuMaterialCache.cacheable(material)) {
                    sample.material = new Material(material);
                    terrainMaterialsReady = false;
                }
                return;
            }
        }
        Renderable sample = new Renderable();
        sample.material = new Material(material);
        sample.environment = environment;
        Mesh layout = new Mesh(true, 0, 0, attributes);
        if (instances != null) {
            VertexAttribute[] placements = new VertexAttribute[instances.size()];
            for (int i = 0; i < placements.length; i++) { placements[i] = instances.get(i).copy(); }
            layout.enableInstancedRendering(true, 1, placements);
        }
        sample.meshPart.set("terrain-material-warmup", layout, 0, 0, GL20.GL_TRIANGLES);
        terrainMaterialSamples.add(sample);
        terrainMaterialsReady = false;
    }

    /** Palette previews own their programs, but borrow the board's already discovered material/layout set. */
    boolean preparePreviewPrograms(GpuTerrain board) {
        for (Renderable sample : board.terrainMaterialSamples) {
            prepareTerrainMaterial(sample.material, sample.meshPart.mesh);
            // A theme's isolated section draws its trees and props without the board's instance batches.
            if (sample.material.has(GpuTreeInstances.Instanced.TYPE)) {
                Material plain = new Material(sample.material);
                plain.remove(GpuTreeInstances.Instanced.TYPE);
                prepareTerrainMaterial(plain, sample.meshPart.mesh.getVertexAttributes());
            }
        }
        GpuBiomeSurface.mark(environment, editable);
        prepareTerrainMaterials();
        return materialsReady();
    }

    /** The plain imported-model layouts used by asset cards; placement/instancing state belongs only to the board. */
    void previewModelMaterials(java.util.function.BiConsumer<Material, VertexAttributes> consumer) {
        for (Renderable sample : terrainMaterialSamples) {
            if (!instancedShader(sample.material)) { continue; }
            Material plain = new Material(sample.material);
            plain.remove(GpuTreeInstances.Instanced.TYPE | GpuBuildingCutaway.TYPE | Foliage.TYPE);
            // Cards bake a selected colour into the raw GLB vertices; only board placements add a slot attribute.
            List<VertexAttribute> attributes = new ArrayList<>();
            for (VertexAttribute attribute : sample.meshPart.mesh.getVertexAttributes()) {
                if (!attribute.alias.equals(RigidGlb.COLOUR_SLOT)) { attributes.add(attribute); }
            }
            consumer.accept(plain, new VertexAttributes(attributes.toArray(VertexAttribute[]::new)));
        }
    }

    /** Driver links continue across loading frames. Retained samples are checked again when lighting flags change. */
    private void prepareTerrainMaterials() {
        if (terrainMaterialSamples.isEmpty() && !columns) {
            // Start the common program while CPU workers build the board. Chunk materials queue any mixed,
            // ice or volcanic variants they actually use; a uniform board never needs a hypothetical blend.
            // The Classic columns draw no sculpted material.
            prepareTerrainMaterial(sculptMaterial(BoardScene.Surface.GRASS), MeshBatch.STANDARD);
        }
        if (terrainMaterialPreparation == null) { terrainMaterialPreparation = new GpuGlsl.Preparation(); }
        // Asking for a program whose link runs assembles its sources again, each loading frame for minutes when nothing
        // is cached: ask again once a link finished, or the samples or the light changed. A change this misses waits
        // for the next link to finish.
        List<Object> asked = List.of(terrainMaterialSamples.size(), environment.getMask(), environmentLighting.flags(),
              environment.shadowMap != null, cacheTops);
        if (asked.equals(terrainMaterialsAsked) && terrainMaterialPreparation.linking()
              && !terrainMaterialPreparation.finished()) {
            return;
        }
        terrainMaterialsAsked = null;
        boolean everySample = true;
        terrainMaterialEnvironment = environment.getMask();
        terrainMaterialFlags = environmentLighting.flags();
        terrainMaterialShadows = environment.shadowMap != null;
        terrainMaterialsReady = true;
        int programs = 0, linked = 0;
        for (Renderable sample : terrainMaterialSamples) {
            boolean twinned = prepasses(prepass(sample.material));
            // A variant whose tops the ground material cache draws also links its generic cached-lit and fill programs.
            boolean cacheable = cacheTops && GpuMaterialCache.cacheable(sample.material);
            programs += (twinned ? 2 : 1) + (editable ? 1 : 0) + (cacheable ? 2 : 0);
            // Beside interactive frames (an edit, the editor's samples) the rest wait once a frame spent its budget.
            if (GpuGlsl.overBudget()) {
                everySample = false;
                continue;
            }
            long start = System.nanoTime();
            boolean colour = terrainMaterialPreparation.run(() ->
                  sample.shader = batch.getShaderProvider().getShader(sample));
            // Its depth twin links here too, after it, behind the loading stage, never at a first draw.
            boolean twin = colour && twinned && terrainMaterialPreparation.run(() ->
                  prepassTwins.put(shaderGroup(sample.shader), GpuShaderProvider.unwrap(twinOf(sample))));
            boolean cast = editable && terrainMaterialPreparation.run(() -> {
                Shader colourShader = sample.shader;
                sample.shader = null;
                try { depthBatch.getShaderProvider().getShader(sample); }
                finally { sample.shader = colourShader; }
            });
            boolean cachedLit = colour && cacheable && terrainMaterialPreparation.run(() -> cacheProgram(sample,
                  GpuMaterialCache.cachedTwin(sample.material), sample.environment, batch.getShaderProvider()));
            boolean fills = colour && cacheable && terrainMaterialPreparation.run(() -> cacheProgram(sample,
                  materialCache.fillMaterial(sample.material), null, fillShaders()));
            GpuGlsl.spent(System.nanoTime() - start);
            linked += (colour ? 1 : 0) + (twin ? 1 : 0) + (cast ? 1 : 0) + (cachedLit ? 1 : 0) + (fills ? 1 : 0);
        }
        if (linked < programs) { terrainMaterialsReady = false; }
        if (everySample) { terrainMaterialsAsked = asked; }
        terrainPrograms = programs;
        terrainProgramsLinked = linked;
    }

    /** Links the program {@code provider} gives a warm-up sample drawn in {@code material} under {@code light}. */
    private static void cacheProgram(Renderable sample, Material material, Environment light, ShaderProvider provider) {
        Renderable draw = new Renderable().set(sample);
        draw.shader = null;
        draw.material = material;
        draw.environment = light;
        provider.getShader(draw);
    }

    /** The ground material cache's fill programs and their batch (drawCacheTops). */
    private ShaderProvider fillShaders() {
        if (fillBatch == null) {
            fillBatch = new ModelBatch(GpuShaderManager.provider("Terrain cache fill",
                  () -> new TerrainShaders(false, true)), new GpuOpaqueSorter());
        }
        return fillBatch.getShaderProvider();
    }

    /** A warm-up sample's depth twin, from the twin provider: never the colour program the sample suggests. */
    private Shader twinOf(Renderable sample) {
        if (prepassShaders == null) {
            prepassShaders = GpuShaderManager.provider("Terrain depth", () -> new TerrainShaders(true));
        }
        Shader colour = sample.shader;
        sample.shader = null;
        try { return prepassShaders.getShader(sample); } finally { sample.shader = colour; }
    }

    private void drainRetired(long deadline) {
        while (!retiredChunks.isEmpty() && System.nanoTime() < deadline) { retiredChunks.removeFirst().dispose(); }
        if (retiredChunks.isEmpty()) {
            ground.releaseRetiredPages();
            decals.releaseRetiredPages();
        }
    }

    private void replaceDetail(int index, Chunk next) {
        scarSurfaceRevision++;
        Chunk old = chunks.set(index, next);
        // Level tops of one family are the same at every detail: the cache keeps their pages and forgets only the old
        // materials. Tops that follow the detail (detailTops) rebuild theirs.
        materialCache.retireTwins();
        if (old.detailTops || next.detailTops) {
            materialCache.invalidate(union(union(new BoundingBox().inf(), old.wallBounds), next.wallBounds));
            int since = materialCache.detailChanged(union(union(new BoundingBox().inf(), old.topBounds),
                  next.topBounds));
            if (next.detailTops) { next.cacheSince = since; }
        }
        editorPropsInstalled = true;
        Chunk evicted = detailCache.remove(index);
        if (evicted != null) { evicted.dispose(); }
        detailCache.put(index, old);
        if (detailCache.size() > 8) {
            var oldest = detailCache.entrySet().iterator();
            oldest.next().getValue().dispose();
            oldest.remove();
        }
        terrainBatch.clear();
        pickingSurfaces.clear();
        detailPixelsPerUnit = Float.NaN;
        // A refined chunk casts its terrain at finer detail. The cached shadow serves until refinement settles
        // (GpuTerrainShadows.render): drawing every chunk and tree again for each one cost 3-6 ms a time on an Iris Xe.
        shadowMap.refined();
        fitShadowBounds();
    }

    /** Fallen limbs are the only terrain props whose geometry follows the unit scale setting. */
    private static boolean hasLimb(BoardScene.Tile tile) {
        return tile.features().stream().anyMatch(feature -> feature.kind() == BoardScene.FeatureKind.LIMB);
    }

    /** Whether a build from {@code next} would draw the same as {@code tiles}'; {@code columns} for a Classic build. */
    private static boolean sameTerrain(List<BoardScene.Tile> tiles, List<BoardScene.Tile> next, boolean columns) {
        if (tiles == next) { return true; }
        if (tiles == null || tiles.size() != next.size()) { return false; }
        for (int i = 0; i < tiles.size(); i++) {
            BoardScene.Tile before = tiles.get(i), after = next.get(i);
            if (!before.sameGeometry(after) || before.blackIce() != after.blackIce()
                  || !Objects.equals(before.decals(), after.decals())
                  || !Objects.equals(before.decalsWithoutLimbs(), after.decalsWithoutLimbs())
                  || columns && !sameColumnArt(before, after)) { return false; }
        }
        return true;
    }

    /** The CPU side of one chunk: pure terrain work that any worker can run, with no GL or renderer state. */
    static Prepared prepare(BoardScene scene, int startX, int startY, float floor, TerrainLod lod,
          Map<Coords, BoardFlow.Current> currents, TerrainSettings settings, Set<Coords> reused,
          Map<Coords, BoardSurface.WaterGeometry> waterShapes, Runnable check, TerrainLoadProgress progress,
          GpuRoads.Masks masks) {
        return prepare(scene, startX, startY, floor, lod, currents, settings, reused, waterShapes, check, progress,
              masks, BoardDecals.index(scene), Map.of());
    }

    private static Prepared prepare(BoardScene scene, int startX, int startY, float floor, TerrainLod lod,
          Map<Coords, BoardFlow.Current> currents, TerrainSettings settings, Set<Coords> reused,
          Map<Coords, BoardSurface.WaterGeometry> waterShapes, Runnable check, TerrainLoadProgress progress,
          GpuRoads.Masks masks, Map<Coords, List<BoardDecals.Stamp>> paint,
          Map<Coords, BoardTacticalGeometry.Surface> paintSurfaces) {
        check.run();
        // Sculpting is pure CPU work per hex: prepare the chunk's surfaces and cliffs in parallel, then build meshes.
        List<BoardScene.Tile> chunkTiles = new ArrayList<>();
        for (int x = startX; x < Math.min(scene.width(), startX + CHUNK_SIZE); x++) {
            for (int y = startY; y < Math.min(scene.height(), startY + CHUNK_SIZE); y++) {
                Coords coords = new Coords(x, y);
                if (!reused.contains(coords)) { chunkTiles.add(scene.tile(coords)); }
            }
        }
        Map<Coords, BoardSurface> surfaces = new java.util.concurrent.ConcurrentHashMap<>();
        progress.begin("surfaces", chunkTiles.size() + reused.size());
        waterShapes.forEach((coords, shape) -> surfaces.put(coords, shape.surface(scene)));
        // An evicted query snapshot is reconstructed only for an edit that actually needs that water again.
        for (Coords coords : reused) {
            if (scene.tile(coords).liquid().present()) {
                surfaces.computeIfAbsent(coords, key -> new BoardSurface(scene, scene.tile(key), lod));
            }
            progress.advance();
        }
        chunkTiles.parallelStream().forEach(tile -> settings.run(() -> {
            check.run();
            surfaces.put(tile.coords(), new BoardSurface(scene, tile, lod));
            progress.advance();
        }));
        // Every neighbour's top is complete before cliff construction borrows it. Each task still owns its walls.
        progress.begin("cliffs", chunkTiles.size());
        chunkTiles.parallelStream().forEach(tile -> settings.run(() -> {
            check.run();
            BoardSurface surface = surfaces.get(tile.coords());
            if (surface.relief.sculpted() || BoardGeometry.tuning().stepsBetweenTops()) {
                surface.walls(scene, floor, surfaces);
            }
            progress.advance();
        }));
        var plantSources = BoardVegetation.prepare(scene, chunkTiles, lod, floor, surfaces);
        Map<Coords, BoardTacticalGeometry.Surface> topography = new java.util.concurrent.ConcurrentHashMap<>();
        topography.putAll(paintSurfaces);
        Map<Coords, BoardPlants> plants = new java.util.concurrent.ConcurrentHashMap<>();
        Map<Coords, SculptPlan> sculpts = new java.util.concurrent.ConcurrentHashMap<>();
        Map<Coords, Map<BoardSurface.Side, List<BoardSurface.Face>>> walls = new java.util.concurrent.ConcurrentHashMap<>();
        progress.begin("topography", chunkTiles.size());
        chunkTiles.parallelStream().forEach(tile -> settings.run(() -> {
            check.run();
            BoardSurface surface = surfaces.get(tile.coords());
            topography.put(surface.tile.coords(), BoardTacticalGeometry.Surface.of(surface, scene, floor, plantSources));
            if (surface.relief.sculpted()) {
                sculpts.put(surface.tile.coords(), prepareSculpt(scene, surface.tile, surface, floor, lod, surfaces));
            } else {
                // Side sampling can scan thousands of top faces. Keep it on the terrain worker, like sculpted cliffs.
                walls.put(tile.coords(), prepareWalls(scene, surface, floor, surfaces));
            }
            progress.advance();
        }));
        // A reused hex whose paint changed grows again on its retained support: no plant grows through paint.
        List<BoardScene.Tile> planting = new ArrayList<>(chunkTiles);
        paintSurfaces.keySet().forEach(at -> planting.add(scene.tile(at)));
        progress.begin("vegetation", planting.size());
        planting.parallelStream().forEach(tile -> settings.run(() -> {
            check.run();
            // Ground cover stands on this finished support and installs with it: it never trails or outlives its ground.
            BoardPlants planted = BoardPlants.plant(scene, tile, topography.get(tile.coords()), lod,
                  paint.getOrDefault(tile.coords(), List.of()));
            if (planted != null) { plants.put(tile.coords(), planted); }
            progress.advance();
        }));
        Infrastructure infrastructure = prepareInfrastructure(scene, chunkTiles, lod, settings, surfaces, check, progress, masks, false);
        progress.begin("water", 2);
        var water = GpuWaterShader.Field.prepare(scene, currents, surfaces);
        progress.advance();
        var lava = GpuWaterShader.Field.prepare(scene, currents, surfaces, true);
        progress.advance();
        progress.begin("masks", 3);
        return new Prepared(surfaces, water, lava, topography, plants, sculpts, currents, infrastructure.roads(),
              infrastructure.bridges(), infrastructure.bridgeShapes(), walls, reused, paint, false);
    }

    /**
     * The roads painting a hex's ground: those nearby at its level ({@link BoardRoad#nearby}) and, in Enhanced, a
     * neighbour's at another level whose carriageway reserves part of this hex.
     */
    private static List<BoardRoad> painting(BoardScene scene, BoardScene.Tile tile, boolean columns) {
        // Enhanced lays a road through water on its bed; Classic's water column shows its liquid only.
        if (tile.liquid().present()) { return columns ? List.of() : BoardRoad.bed(scene, tile); }
        var result = new ArrayList<>(BoardRoad.nearby(scene, tile));
        for (int d = 0; d < 6 && !columns; d++) {
            var source = scene.neighbor(tile, d);
            if (source != null && source.elevation() != tile.elevation() && BoardRoad.rendered(source)
                  && BoardRoadGrade.reaches(scene, source, tile)) { result.add(BoardRoad.of(scene, source)); }
        }
        return result;
    }

    /**
     * What a road's paint mask depends on (GpuRoads.Masks#mask): its hex, the tiles its road and paint read
     * ({@link #roadInputs}), the graphics and its place among the hex's patches.
     */
    private record RoadMaskKey(Coords source, List<BoardScene.Tile> inputs, boolean columns, int patch) { }

    /**
     * The tiles a road's paint can read: its own hex's and those within three hexes, which take in its neighbours' roads,
     * a shared roundabout's members and a bridge's banks.
     */
    private static List<BoardScene.Tile> roadInputs(BoardScene scene, Coords at) {
        List<BoardScene.Tile> result = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                Coords coords = new Coords(at.getX() + dx, at.getY() + dy);
                if (coords.distance(at) <= 3) { result.add(scene.tile(coords)); }
            }
        }
        return result;
    }

    /** Shared carriageways, paint and rail supports; Classic natural decks use a full hex cap. */
    private record Infrastructure(Map<Coords, List<RoadPatch>> roads, Map<Coords, BoardBridge.Deck> bridges,
          Map<Coords, BoardBridge.Shape> bridgeShapes) { }

    private static Infrastructure prepareInfrastructure(BoardScene scene, List<BoardScene.Tile> chunkTiles,
          TerrainLod lod, TerrainSettings settings, Map<Coords, BoardSurface> surfaces, Runnable check,
          TerrainLoadProgress progress, GpuRoads.Masks masks, boolean columns) {
        Map<Coords, List<RoadPatch>> roads = new java.util.concurrent.ConcurrentHashMap<>();
        Map<Coords, BoardBridge.Deck> bridges = new java.util.concurrent.ConcurrentHashMap<>();
        Map<Coords, BoardBridge.Shape> bridgeShapes = new java.util.concurrent.ConcurrentHashMap<>();
        progress.begin("roads", chunkTiles.size());
        chunkTiles.parallelStream().forEach(tile -> settings.run(() -> {
            check.run();
            BoardSurface surface = surfaces.get(tile.coords());
            List<RoadPatch> patches = new ArrayList<>();
            var mineralGround = columns ? null : GpuMineralGround.prepare(scene, tile, surface);
            if (mineralGround != null) {
                patches.add(new RoadPatch(mineralGround.patch(), masks.share(mineralGround.mask()),
                      mineralGround.triangles(), false, tile));
            }
            for (BoardRoad shared : surface == null ? List.<BoardRoad>of() : painting(scene, surface.tile, columns)) {
                var source = scene.tile(shared.coords());
                // A road ending on a cliff's top wears out to gravel at the rim (Enhanced).
                BoardRoad road = columns ? shared : GpuRoads.painted(scene, source, shared);
                List<BoardScene.Tile> inputs = roadInputs(scene, source.coords());
                int index = 0;
                for (var patch : GpuRoads.patches(scene, source, road, shared)) {
                    check.run();
                    road.clipPaint(patch.shape());
                    var triangles = GpuRoads.drape(source, surface, patch);
                    var key = new RoadMaskKey(source.coords(), inputs, columns, index++);
                    if (!triangles.isEmpty() && !patch.shape().isEmpty()) {
                        var mask = masks.mask(key, () -> GpuRoads.mask(road, patch), check);
                        float scale = BoardGeometry.hexScale();
                        mask = new GpuRoads.MaskData(mask.pixels(),
                              mask.x() + (BoardGeometry.centerX(source.coords()) - BoardGeometry.centerX(tile.coords())) / scale,
                              mask.y() + (BoardGeometry.centerY(source.coords()) - BoardGeometry.centerY(tile.coords())) / scale,
                              mask.width(), mask.height());
                        patches.add(new RoadPatch(patch, masks.share(mask), triangles, false, source));
                    }
                }
                // A road into a portal carries its verges and markings on along the tunnel's floor.
                for (var tunnel : columns || source != tile ? List.<BoardTunnel>of() : BoardTunnel.entrances(scene, tile)) {
                    if (tunnel.bridge()) { continue; }
                    BoardRoad through = GpuRoads.through(shared, tunnel);
                    for (var patch : GpuRoads.tunnelPatches(tile, through, tunnel)) {
                        patches.add(new RoadPatch(patch, masks.share(GpuRoads.tunnelMask(tile, through, patch, tunnel)),
                              GpuRoads.tunnelFloor(tunnel, patch), true, tile));
                    }
                }
            }
            for (var feature : tile.features()) {
                if (!feature.asset().equals("bridge")) { continue; }
                var deck = BoardBridge.deck(scene, tile);
                bridges.put(tile.coords(), deck);
                // A rock arch has no piers. A built deck's piers join its shape: drawn, shadowed and picked with it in every view.
                // A deck's road is painted on its own sides; a clean deck shows its top (builtBridge, a rock arch's rock).
                if (deck.natural()) {
                    var arch = BoardNaturalBridge.build(scene, tile, deck, lod, surfaces, columns);
                    bridgeShapes.put(tile.coords(), arch);
                    if (deck.kind() == BoardRoad.Kind.NONE) { continue; }
                    // A road on a rock arch lies on its top, as rails do.
                    BoardRoad road = deck.road(tile.coords()).approachMarkings(scene, tile, true)
                          .control(megamek.common.board.HexAppearance.trafficControl(tile.appearance(), "bridgeRoad", deck.roadExits()));
                    for (var patch : GpuRoads.deckPatches(tile, deck, road)) {
                        check.run();
                        if (patch.shape().isEmpty()) { continue; }
                        patches.add(new RoadPatch(patch, masks.share(GpuRoads.mask(road, patch)),
                              GpuRoads.deck(patch, arch, 0), true, tile));
                    }
                    continue;
                }
                var footing = BoardBridgeFooting.build(scene, tile, lod, surfaces, columns);
                var shape = footing.withDeck(scene, tile, deck, lod, surfaces, columns);
                if (!shape.facets().isEmpty()) { bridgeShapes.put(tile.coords(), shape); }
                if (deck.kind() == BoardRoad.Kind.NONE) { continue; }
                var painted = footing.paint(deck);
                BoardRoad road = painted.road(deck, tile.coords()).approachMarkings(scene, tile, true)
                      .control(megamek.common.board.HexAppearance.trafficControl(tile.appearance(), "bridgeRoad", deck.roadExits()));
                for (var patch : GpuRoads.deckPatches(tile, deck, road, painted)) {
                    check.run();
                    if (patch.shape().isEmpty()) { continue; }
                    patches.add(new RoadPatch(patch, masks.share(GpuRoads.mask(road, patch)),
                          GpuRoads.deck(tile, feature, patch, painted, surfaces, shape), true, tile));
                }
            }
            if (!patches.isEmpty()) { roads.put(tile.coords(), patches); }
            progress.advance();
        }));
        // A ground rail can ride a ramp owned by a reused bridge or one in the next chunk. Prepare that
        // support on this worker too; route building must not depend on which chunk installs first.
        for (var tile : chunkTiles) {
            for (var feature : tile.features()) {
                for (var next : BoardRail.approaches(scene, tile, feature)) {
                    check.run();
                    bridgeShapes.computeIfAbsent(next.coords(), at -> {
                        var deck = BoardBridge.deck(scene, next);
                        return deck.natural() ? BoardNaturalBridge.build(scene, next, deck, lod, surfaces, columns)
                              : BoardBridgeFooting.build(scene, next, lod, surfaces, columns)
                                    .withDeck(scene, next, deck, lod, surfaces, columns);
                    });
                }
            }
        }
        return new Infrastructure(roads, bridges, bridgeShapes);
    }

    /** Natural walls are the completed surface mesh, shared with picking; legacy skirts still follow their sides. */
    static Map<BoardSurface.Side, List<BoardSurface.Face>> prepareWalls(BoardScene scene, BoardSurface surface,
          float floor, Map<Coords, BoardSurface> surfaces) {
        Map<BoardSurface.Side, List<BoardSurface.Face>> result = new LinkedHashMap<>();
        Set<Integer> natural = new HashSet<>();
        for (BoardSurface.Side side : surface.sides(scene, floor, surfaces)) {
            if (BoardFeatures.shaft(surface.tile)) {
                result.put(side, surface.straightWalls(List.of(side)));
            } else if (surface.relief.naturalEdge(side.edge())) {
                if (natural.add(side.edge())) {
                    result.put(side, surface.walls(scene, floor, surfaces).stream()
                          .filter(face -> face.landEdge() == side.edge()).toList());
                }
            } else {
                result.put(side, surface.relief.walls(List.of(side), surfaces));
            }
        }
        return result;
    }

    /** Collect the existing approach faces for concrete and leave the remaining terrain on its native material. */
    private static List<BoardSurface.Face> collectBridgeSupports(BoardSurface surface, List<BoardSurface.Face> faces,
          List<BoardSurface.Face> supports) {
        if (surface.ramps == 0) { return faces; }
        var ground = new ArrayList<BoardSurface.Face>();
        for (var face : faces) {
            if (surface.bridgeSupport(face)) { supports.add(face); }
            else { ground.add(face); }
        }
        return ground;
    }

    /**
     * A Classic hex: its tileset column, then the features a Classic build keeps
     * ({@link #columnFeature}) standing and painted on that column.
     */
    private void collectColumn(ChunkBuild build, BoardScene.Tile tile) {
        Coords coords = tile.coords();
        var column = build.prepared.topography().get(coords);
        GpuTilesetTerrain.write(tile, column,
              build.prepared.currents().getOrDefault(coords, BoardFlow.Current.STILL),
              at -> build.scene.tile(at).tileset() == null ? null : ground.region(new GroundSlot(at, false)),
              assets, build.layers.get(0)::add, build.layers.get(3)::add, build.animations, build.chunk.bounds);
        if (tile.frozen() && tile.water()) {
            // Enhanced's ice sheet, just above the still water the column shows beneath it.
            float lift = GpuTilesetTerrain.LIQUID_LIFT * BoardGeometry.hexScale();
            build.layers.get(3).addTriangles(lakeIce(), meshes -> {
                for (var face : column.top()) {
                    iceTriangle(meshes.get(), new BoardSurface.Face(new Vector3(face.a()).add(0, 0, lift),
                          new Vector3(face.b()).add(0, 0, lift), new Vector3(face.c()).add(0, 0, lift),
                          BoardSurface.Finish.ICE), point -> Color.WHITE);
                }
            });
        }
        roads(build.layers.get(2), build.prepared.roads().getOrDefault(coords, List.of()),
              build.prepared.surfaces().get(coords), build.chunk.roadMasks, build.scene, tile, null);
        collectFeatures(build, tile, build.prepared.surfaces().get(coords), ground(build, coords));
    }

    private void collectTile(ChunkBuild build, BoardScene.Tile tile) {
        // Portals close the cliff cut in every build, including bridges suspended over space or sky.
        collectTunnels(build, tile);
        if (build.chunk.columns) {
            collectColumn(build, tile);
            return;
        }
        BoardScene scene = build.scene;
        Prepared prepared = build.prepared;
        Map<Coords, BoardSurface> surfaces = prepared.surfaces();
        Chunk chunk = build.chunk;
        float floor = build.floor;
        TerrainLod lod = chunk.lod;
        GpuTerrainLayer solid = build.layers.get(0), scatter = build.layers.get(1), overlay = build.layers.get(2),
              liquid = build.layers.get(3);
        Map<String, LiquidSurface> animations = build.animations;
        BoardSurface surface = surfaces.get(tile.coords());
        if (tile.backdrop().present()) {
            for (var face : surface.faces) {
                chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c());
                if (tile.backdrop() == BoardBackdrop.SKY) {
                    solid.add(GpuBackdrop.material(tile.backdrop()), mesh -> surface(mesh, tile.coords(), face, null, 0));
                }
            }
            // Space is completely transparent. Its retained CPU surface supports picking and overlays only;
            // the atmosphere's shared skybox shows through without an opaque colour or depth plane.
            // Bridges and explicit structures remain physical objects over the aerospace background.
            roads(overlay, prepared.roads().getOrDefault(tile.coords(), List.of()), surface, chunk.roadMasks, scene, tile, null);
            collectFeatures(build, tile, surface, new GroundView(surface.faces, BoardGeometry.groundZ(tile),
                  prepared.bridgeShapes().get(tile.coords())));
            return;
        }
        List<BoardSurface.Face> smoothTop = new ArrayList<>();
        List<BoardSurface.Face> outcrops = new ArrayList<>();
        List<BoardSurface.Face> bridgeSupports = new ArrayList<>();
        boolean sculpted = surface.relief.sculpted();
        Material magma = tile.liquid().volcanic() ? GpuMagmaShader.material(assets,
              tile.liquid().molten() ? GpuMagmaShader.BANK : GpuMagmaShader.CRUST, chunk.lavaField) : null;
        // A one-hex palette card exposes this unblended volcanic edge even when the board's banks all blend.
        if (editable && magma != null) { prepareTerrainMaterial(magma, MeshBatch.STANDARD); }
        if (sculpted) {
            sculpt(solid, overlay, chunk, scene, tile, surface, surfaces, prepared.sculpts().get(tile.coords()), magma);
        }
        // Field stones and shrubs share the scatter pass, while retaining their canonical picking/support faces.
        var fieldScatter = surface.faces.stream().filter(BoardSurface.Face::scatter).toList();
        if (!fieldScatter.isEmpty()) {
            BoundingBox bounds = new BoundingBox().inf();
            for (var face : fieldScatter) { bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
            chunk.bounds.ext(bounds);
            chunk.scatterDiameter = Math.max(chunk.scatterDiameter, bounds.getDimensions(new Vector3()).len());
            for (var group : byFamily(surface, fieldScatter).entrySet()) {
                Material material = sculptMaterial(group.getKey());
                if (sculpted && !tile.liquid().present()) { material = GpuIceShader.ground(material, scene, tile); }
                float shore = tile.liquid().present() ? GpuWaterShader.palette(tile.liquid()) : Float.NaN;
                scatter.addTriangles(material, mesh -> sculptedFaces(mesh, surface, group.getValue(), shore, scene, surfaces));
            }
        }
        if (tile.frozen() && tile.water()) {
            // One shared material in the existing transparent pass reveals the already-rendered bed/units.
            // No per-hex texture, refraction target or additional surface mesh is needed.
            liquid.addTriangles(lakeIce(), meshes -> drawnIce(meshes, surface));
            if (!surface.waterfalls.isEmpty()) {
                Material frozenFall = GpuIceShader.waterfall(material(assets.ice().color(), true));
                frozenFall.set(new Ground(1), IntAttribute.createCullFace(GL20.GL_BACK));
                for (var frozen : surface.frozenFalls()) {
                    var frozenFaces = frozen.faces();
                    var frozenNormals = wallNormals(frozenFaces);
                    liquid.addTriangles(frozenFall, meshes -> {
                        for (int index = 0; index < frozenFaces.size(); index++) {
                            iceTriangle(meshes.get(), frozenFaces.get(index), point -> Color.WHITE, frozenNormals,
                                  frozen.uv(), index * 6);
                        }
                    });
                    for (var face : frozenFaces) { chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
                }
            }
        }
        for (BoardSurface.Face face : sculpted ? List.<BoardSurface.Face>of() : surface.faces) {
            if (face.scatter()) { continue; }
            // Rough cover and dug ground are drawn by their models.
            if (face.finish() == BoardSurface.Finish.ROUGH || face.finish() == BoardSurface.Finish.DUG) { continue; }
            if (face.finish() == BoardSurface.Finish.ICE) { continue; }
            chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c());
            // A Solaris elevator's platform draws its own top (BoardElevator).
            if (face.finish() == BoardSurface.Finish.TOP && BoardFeatures.shaft(tile)) { continue; }
            if (surface.bridgeSupport(face)) { bridgeSupports.add(face); continue; }
            if (tile.ultraSublevel()) {
                solid.add(pitMaterial(), mesh -> surface(mesh, tile.coords(), face, null, 0));
                continue;
            }
            // Flat road hexes keep their ground path, but their rocks use the same geology as sculpted hexes.
            if (magma == null && face.finish() == BoardSurface.Finish.OUTCROP) { outcrops.add(face); continue; }
            // The physical family mapping on the retained road/approach/body geometry; volcanic ground its magma.
            smoothTop.add(face);
        }
        for (var group : byFamily(surface, outcrops).entrySet()) {
            float shore = tile.liquid().present() ? GpuWaterShader.palette(tile.liquid()) : Float.NaN;
            solid.addTriangles(sculptMaterial(group.getKey()),
                  mesh -> sculptedFaces(mesh, surface, group.getValue(), shore, scene, surfaces));
        }
        if (!sculpted && !smoothTop.isEmpty()) {
            Map<Vector3, Vector3> normals = wallNormals(smoothTop);
            blended(solid, GpuSurfaceBlend.prepare(scene, tile, smoothTop,
                  p -> vertex(p, normals.get(p), 99, 99,
                        new Color(1, (tile.elevation() + 64) / 255f, 0, 1))), scene, tile, chunk.lavaField);
        }
        if (!sculpted && !surface.retainingWalls.isEmpty()) {
            var walls = collectBridgeSupports(surface, surface.retainingWalls, bridgeSupports);
            blended(solid, GpuSurfaceBlend.prepare(scene, tile, walls,
                  p -> sculptVertex(p, surface.relief.shade(p), Float.NaN, surface)), null, null, chunk.lavaField);
            for (var face : surface.retainingWalls) { chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
        }
        if (!surface.containmentWalls.isEmpty()) {
            var panels = surface.containmentWalls;
            // Precast retaining walls share the tunnel portals' concrete.
            solid.add(material(assets.material("scenery/tunnel-concrete"), false), mesh -> {
                for (var face : panels) { panel(mesh, face); }
            });
            for (var face : panels) { chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
        }
        if (!surface.pouredWalls.isEmpty()) {
            var poured = surface.pouredWalls;
            // A road built in concrete is walled in natural ground as in the concrete hex it ramps from.
            solid.addTriangles(sculptMaterial(BoardScene.Surface.CONCRETE),
                  mesh -> sculptedFaces(mesh, surface, poured, Float.NaN, scene, surfaces));
            for (var face : poured) { chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
        }
        boolean curving = sculpted && chunk.tessellated && chunk.lod == TerrainLod.FULL && GpuTessellation.available();
        roads(overlay, prepared.roads().getOrDefault(tile.coords(), List.of()), surface, chunk.roadMasks, scene, tile,
              curving ? coatSupports(prepared.sculpts().get(tile.coords()), surface, scene, surfaces) : null);
        if (BoardFeatures.shaft(tile)) {
            // A Solaris elevator's platform, and raised, its lift open beneath it, down to the lowest ground beside it;
            // the hex's top and straight walls stay for picking only.
            List<BoardSurface.Face> shaftWalls = new ArrayList<>();
            prepared.walls().getOrDefault(tile.coords(), Map.of()).values().forEach(shaftWalls::addAll);
            var lift = BoardElevator.build(scene, tile.coords(), BoardGeometry.surfaceZ(tile), shaftWalls);
            solid.lift = true;
            addLift(solid, lift);
            solid.lift = false;
            lift.parts().values().forEach(faces -> faces.forEach(face -> chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c())));
        }
        for (var wall : prepared.walls().getOrDefault(tile.coords(), Map.of()).entrySet()) {
            BoardSurface.Side side = wall.getKey();
            if (BoardFeatures.shaft(tile)) { continue; }
            if (magma != null) {
                blended(solid, GpuSurfaceBlend.prepare(scene, tile, wall.getValue(),
                      p -> sculptVertex(p, surface.relief.shade(p), Float.NaN, surface)), null, null, chunk.lavaField);
                for (var face : wall.getValue()) {
                    chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c());
                }
                continue;
            }
            for (var face : wall.getValue()) { chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
            var wallFaces = collectBridgeSupports(surface, wall.getValue(), bridgeSupports);
            if (wallFaces.isEmpty()) { continue; }
            if (surface.relief.naturalEdge(side.edge())) {
                blended(solid, GpuSurfaceBlend.prepare(scene, tile, wallFaces,
                      p -> sculptVertex(p, surface.relief.shade(p), Float.NaN, surface)), null, null, chunk.lavaField);
                continue;
            }
            solid.addTriangles(sculptMaterial(tile.surface()),
                  mesh -> sculptedFaces(mesh, surface, wallFaces, Float.NaN, scene, surfaces));
        }
        if (!bridgeSupports.isEmpty()) {
            float coverage = assets.coverage("terrain/surfaces/concrete-ground");
            solid.addTriangles(material(assets.material("terrain/surfaces/concrete-ground"), false), meshes -> {
                for (var face : bridgeSupports) {
                    var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                    bridgeFace(meshes.get(), face.a(), face.b(), face.c(), normal, Color.WHITE, coverage);
                }
            });
        }
        if (!surface.water.isEmpty() || !surface.waterFaces.isEmpty()) {
            BoardLiquid.Textures source = tile.liquid().textures(tile.waterDepth());
            BoardFlow.Current current = prepared.currents().getOrDefault(tile.coords(), BoardFlow.Current.STILL);
            GpuWaterShader.Field field = tile.liquid().molten() ? chunk.lavaField : chunk.waterField;
            Material water = liquidMaterial(scene, surface, source, false, field);
            boolean animatedFrames = !water.has(GpuMagmaShader.TYPE) && (!proceduralWater || tile.liquid().molten());
            // Authored frames scroll their own texture with the hex's current; procedural water reads the
            // field's blended current instead, so it shares one material with its neighbours.
            if (animatedFrames) {
                water.id += ":" + current;
                animations.put(water.id, new LiquidSurface(water, source, false, current));
            }
            TextureAttribute waterTexture = water.get(TextureAttribute.class, TextureAttribute.Diffuse);
            // Magma maps itself in world space; its sheet keeps texture coordinates that span the hex like artwork.
            TextureRegion waterArt = new TextureRegion(waterTexture == null ? rainNoise : waterTexture.textureDescription.texture);
            // Molten material writes opaque depth; water and hazardous pools reveal their beds and units.
            GpuTerrainLayer destination = tile.liquid().molten() ? solid : liquid;
            List<BoardSurface.Face> waterFaces = proceduralWater && !tile.liquid().molten()
                  ? GpuWaterWaves.faces(surface.waterFaces, lod) : surface.waterFaces;
            Map<Vector3, Vector3> waterNormals = wallNormals(waterFaces);
            if (tile.liquid().molten() && !water.has(GpuMagmaShader.TYPE)) {
                for (BoardSurface.Face face : waterFaces) {
                    destination.add(water, mesh -> surface(mesh, tile.coords(), face, waterArt, 0));
                }
            } else {
                destination.add(water, mesh -> waterSurface(mesh, field, tile.coords(), waterFaces,
                      proceduralWater ? null : waterArt, waterNormals));
            }
            for (BoardSurface.Face face : waterFaces) {
                chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c());
            }
            Material cut = water;
            if (!surface.cutFaces.isEmpty() && !tile.liquid().molten()) {
                cut = GpuWaterPages.cutMaterial(water);
            }
            for (BoardSurface.Face face : surface.cutFaces) {
                if (tile.liquid().molten()) {
                    // A wall of lava, mapped like other walls instead of smeared down from its top.
                    destination.add(water, mesh -> surface(mesh, tile.coords(), face, null, 0));
                } else {
                    destination.add(cut, mesh -> waterCut(mesh, BoardGeometry.waterZ(tile), face));
                }
                chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c());
            }
            if (!tile.frozen() && !surface.waterfalls.isEmpty()) {
                Material fall = liquidMaterial(scene, surface, source, true, field);
                if (animatedFrames) { animations.put(fall.id, new LiquidSurface(fall, source, true, BoardFlow.Current.STILL)); }
                Material back = null, spray = null;
                GpuWaterShader sheet = fall.get(GpuWaterShader.class, GpuWaterShader.TYPE);
                if (!tile.liquid().molten()) {
                    fall.set(IntAttribute.createCullFace(GL20.GL_BACK));
                    // Keep the inward face visible through the upper surface, with half the front's opacity.
                    back = new Material(fall);
                    back.set(new BlendingAttribute(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA, 0.4f));
                    back.set(IntAttribute.createCullFace(GL20.GL_FRONT));
                }
                if (sheet != null) {
                    // Spray is launched by the water's vertex shader, always at full opacity and from its own
                    // material, so authored frames never swap its shader; every fall's spray shares it.
                    spray = new Material(fall);
                    spray.id = fall.id + ":spray";
                    spray.remove(GpuLiquidShader.Frame.TYPE);
                    spray.set(new BlendingAttribute(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA, 1),
                          sheet.spray());
                }
                for (BoardSurface.Side drop : surface.waterfalls) {
                    destination.add(fall, mesh -> GpuWaterfall.sheet(mesh, surface, drop, chunk.waterField));
                    if (back != null) {
                        destination.add(back, mesh -> GpuWaterfall.sheet(mesh, surface, drop, chunk.waterField));
                    }
                    if (spray != null) {
                        destination.add(spray, mesh -> GpuWaterfall.spray(mesh, surface, drop));
                    }
                    // The fall lands out in the receiving hex and throws its spray up around there.
                    Vector3[][] grid = GpuWaterfall.grid(surface, drop);
                    float around = spray == null ? 0 : GpuWaterfall.sprayReach();
                    float above = spray == null ? 0 : GpuWaterfall.sprayHeight(BoardGeometry.waterZ(tile) - drop.lowA());
                    for (Vector3[] column : grid) {
                        for (Vector3 p : column) { chunk.bounds.ext(p); }
                        Vector3 landing = column[column.length - 1];
                        chunk.bounds.ext(landing.x - around, landing.y - around, landing.z)
                              .ext(landing.x + around, landing.y + around, landing.z + above);
                    }
                }
            }
        }
        if (tile.surface() == BoardScene.Surface.FUNGUS) {
            List<BoardSurface.Face> cliffs = new ArrayList<>(surface.faces);
            cliffs.addAll(surface.walls(scene, floor, surfaces));
            for (var placement : BoardFungus.cliffs(scene, tile, cliffs)) {
                ModelInstance instance = new ModelInstance(foliage(assets.lodModel(placement.asset(), 0)));
                instance.transform.set(placement.transform());
                BoundingBox bounds = instance.calculateBoundingBox(new BoundingBox()).mul(instance.transform);
                var prop = new Prop(tile.coords(), instance, bounds, placement.asset(), false);
                prop.scatter = true;
                chunk.props.add(prop);
                chunk.bounds.ext(bounds);
            }
        }
        for (BoardRough.Placement placement : surface.roughModels()) {
            ModelInstance instance = new ModelInstance(assets.model(placement.asset()));
            instance.transform.set(placement.transform());
            BoundingBox bounds = instance.calculateBoundingBox(new BoundingBox()).mul(instance.transform);
            var rough = new Prop(tile.coords(), instance, bounds, null, false);
            rough.tactical = true;
            chunk.props.add(rough);
            chunk.bounds.ext(bounds);
        }
        collectFeatures(build, tile, surface, new GroundView(surface.faces, BoardGeometry.groundZ(tile),
              prepared.bridgeShapes().get(tile.coords())));
    }

    /** Portals belong to their road in every graphics mode and in the Tactical View. */
    private void collectTunnels(ChunkBuild build, BoardScene.Tile tile) {
        for (var tunnel : BoardTunnel.entrances(build.scene, tile)) {
            Model model = assets.model(tunnel.asset());
            if (model == null) { continue; }
            ModelInstance instance = new ModelInstance(model);
            // The road continues inside with the same world UVs, normal/surface maps and weather response as its approach.
            Material tunnelFloor = instance.getMaterial("tunnel-floor");
            String road = GpuRoads.texture(tunnel.kind());
            tunnelFloor.set(roadMaterial(road.substring("roads/".length()), false));
            tunnelFloor.set(new BridgeDeck(assets.roadCoverage(road)));
            instance.transform.set(tunnel.transform());
            BoundingBox bounds = instance.calculateBoundingBox(new BoundingBox()).mul(instance.transform);
            // The road's portal stays in the Tactical View, as the road does.
            var portal = new Prop(tile.coords(), instance, bounds, null, false);
            portal.tactical = true;
            build.chunk.props.add(portal);
            build.chunk.bounds.ext(bounds);
        }
    }

    /**
     * What a hex's placements stand and paint on: the faces of its 3D surface, or with the Classic
     * graphics its column's, the height of the ground where none covers a point, and its installed bridge deck or null.
     */
    private record GroundView(List<BoardSurface.Face> faces, float fallback, BoardBridge.Shape deck) {
        float height(float x, float y) { return BoardSurface.sampleHeight(faces, x, y, fallback); }

        /** This ground as it was before scenery dug into it ({@link BoardGroundCut}). */
        GroundView undug() {
            return new GroundView(faces.stream().filter(face -> face.finish() != BoardSurface.Finish.DUG).toList(),
                  fallback, deck);
        }
    }

    /** The {@link GroundView} of {@code coords} in {@code build}: its 3D surface (built if outside the chunk), or column. */
    private static GroundView ground(ChunkBuild build, Coords coords) {
        BoardScene.Tile tile = build.scene.tile(coords);
        if (build.chunk.columns) {
            var column = build.prepared.topography().get(coords);
            if (column == null) {
                var surfaces = build.prepared.surfaces();
                var carrier = GpuTilesetTerrain.infrastructure(build.scene, tile)
                      ? surfaces.computeIfAbsent(coords, at -> BoardSurface.columns(build.scene, tile, build.chunk.lod)) : null;
                column = GpuTilesetTerrain.column(build.scene, tile, build.floor, carrier, surfaces);
            }
            return new GroundView(column.faces(), BoardGeometry.groundZ(tile),
                  build.prepared.bridgeShapes().get(coords));
        }
        BoardSurface surface = build.prepared.surfaces().computeIfAbsent(coords,
              at -> new BoardSurface(build.scene, tile, build.chunk.lod));
        return new GroundView(surface.faces, BoardGeometry.groundZ(tile), build.prepared.bridgeShapes().get(coords));
    }

    /** Scenery the Tactical View keeps, as the game counts it: rubble, fortifications and cargo on the ground. */
    private static boolean gameplayScenery(ModelInstance instance) {
        for (Material material : instance.materials) {
            if (material.id.startsWith("rubble-") || material.id.startsWith("fortified-") || material.id.startsWith("cargo-")) {
                return true;
            }
        }
        return false;
    }

    /**
     * What the Classic graphics build of a hex's features: buildings, fuel tanks and industrial
     * plants, generated bridges, rail and maglev routes, placed objects other than plants, a dug-in platoon's trench and
     * cargo set down, which no tileset art draws. The tileset art draws the rest.
     */
    private boolean columnFeature(BoardScene.Feature feature) {
        return feature.asset().equals("bridge") || feature.asset().equals(BoardFeatures.TRENCH)
              || feature.asset().equals(BoardFeatures.CARGO)
              || feature.kind() == BoardScene.FeatureKind.BUILDING || feature.asset().startsWith("buildings/")
              || feature.decoration() != null
              && (BoardRail.route(feature) || !foliageModel(assets.slotted(feature.asset())));
    }

    /**
     * A hex's features: its buildings, structures, bridges, trees, scatter and placed objects, and its paint. A Classic
     * build keeps those {@link #columnFeature} names; both use the same road/rail carrier and generated deck shapes.
     */
    private void collectFeatures(ChunkBuild build, BoardScene.Tile tile, BoardSurface surface, GroundView ground) {
        BoardScene scene = build.scene;
        Prepared prepared = build.prepared;
        Map<Coords, BoardSurface> surfaces = prepared.surfaces();
        Chunk chunk = build.chunk;
        TerrainLod lod = chunk.lod;
        GpuTerrainLayer solid = build.layers.get(0), scatter = build.layers.get(1);
        var fitted = BoardFeatures.fittedControls(scene, tile);
        // A biodome's garden trees (BoardMultiHexBuilding#withTrees) draw as trees but are no woods.
        var features = BoardMultiHexBuilding.withTrees(scene, tile, fitted);
        if (features.stream().anyMatch(f -> f.kind() == BoardScene.FeatureKind.SCENERY || f.authoredPlacement() || f.decoration() != null)) {
            // Install solid supports first, regardless of the captured artwork layer order.
            features = features.stream().sorted(Comparator.comparing(f -> f.kind() == BoardScene.FeatureKind.SCENERY
                  || f.authoredPlacement() || f.decoration() != null)).toList();
        }
        List<Prop> supports = new ArrayList<>();
        for (BoardScene.Feature feature : features) {
            if (build.preview != null && !routeFeature(tile, feature)) { continue; }
            if (feature.decoration() != null && feature.decoration().kind().equals("decal")) { continue; }
            if (build.chunk.columns && !columnFeature(feature)) { continue; }
            // Rough boulders are already part of the shared terrain mesh, shading and picking geometry.
            if (feature.kind() == BoardScene.FeatureKind.BOULDER || feature.kind() == BoardScene.FeatureKind.ROUGH
                  || feature.kind() == BoardScene.FeatureKind.SHAFT) { continue; }
            boolean fungus = BoardFungus.asset(feature.asset());
            boolean fungusScatter = fungus && feature.kind() == BoardScene.FeatureKind.SCATTER;
            if (fungusScatter && (!BoardScatter.allowed(tile) || tile.liquid().present() || surface.ramps != 0)) { continue; }
            if (feature.kind() == BoardScene.FeatureKind.SCATTER && !fungus) {
                // Road approaches can extend into a hex that has no road terrain of its own.
                if (!tile.liquid().present() && surface.ramps == 0
                      && !(tile.detailedGround() && feature.asset().equals("scatter-grass"))) {
                    Material material;
                    if (feature.asset().equals("scatter-plant")) {
                        // The same authored leaf/stem material as field shrubs, with unchanged scatter placement.
                        material = sculptMaterial(tile.surface());
                    } else {
                        if (build.scatterMaterial == null) {
                            build.scatterMaterial = new Material(ColorAttribute.createDiffuse(Color.WHITE),
                                  TextureAttribute.createDiffuse(assets.scatter()), new GpuLinearColors());
                        }
                        material = build.scatterMaterial;
                    }
                    scatter.add(material, mesh -> GpuScatter.build(mesh, tile, surface, feature));
                    chunk.scatterDiameter = Math.max(chunk.scatterDiameter, GpuScatter.diameter(feature));
                    chunk.bounds.ext(BoardGeometry.centerX(tile.coords()), BoardGeometry.centerY(tile.coords()),
                          (tile.elevation() + feature.height()) * BoardGeometry.level());
                }
                continue;
            }
            boolean limb = feature.kind() == BoardScene.FeatureKind.LIMB;
            boolean bridge = feature.asset().equals("bridge");
            var bridgeShape = bridge ? prepared.bridgeShapes().get(tile.coords()) : null;
            if (bridgeShape != null) {
                chunk.tileMeshes.get(tile.coords()).bridgeShape = bridgeShape;
                chunk.bounds.ext(bridgeShape.bounds());
            }
            if (bridge) {
                solid.route = true;
                var deck = prepared.bridges().get(tile.coords());
                if (deck.natural()) {
                    // A cap has art only where a bank of the span's surface abuts it; else the arch shows its rock.
                    TextureRegion cap = chunk.columns && GpuTilesetTerrain.naturalBridgeArt(scene, tile, deck) != null
                          ? this.ground.region(new GroundSlot(tile.coords(), true)) : null;
                    naturalBridge(solid, bridgeShape, tile.deckMud(), cap != null,
                          !chunk.columns && BoardNaturalBridge.overhang(scene, tile));
                    if (cap != null) { GpuTilesetTerrain.naturalBridgeTop(tile, bridgeShape, cap, solid::add); }
                } else { builtBridge(solid, bridgeShape, deck, scene, tile); }
                solid.route = false;
                continue;
            }
            boolean generatedIndustrial = BoardIndustrial.supports(feature);
            boolean wallBuilding = BoardWall.building(tile.cover(), feature);
            var wallLayout = BoardWall.supports(feature.asset()) ? BoardWall.layout(scene, tile, feature) : null;
            var multiHexLayout = BoardMultiHexBuilding.supports(feature.asset()) ? BoardMultiHexBuilding.layout(scene, tile, feature) : null;
            GpuBuilding.Assembly building = generatedIndustrial || feature.decoration() != null ? null : assets.building(feature.asset(), Math.round(feature.height()),
                  ConnectedKit.reach(scene, tile, Math.round(feature.height())), GpuBuilding.seed(tile, feature),
                  feature.kind() == BoardScene.FeatureKind.BUILDING && !wallBuilding);
            // A placed object draws the same way in every view: one shared mesh per model and level (GpuAssets#slotted),
            // its colours passed per placement, its parts instanced (Chunk#cacheDecorations). Rail and maglev routes keep
            // their own route caching. What a rail owns (its buffer stops) is drawn as an object but never edited as one.
            boolean placedObject = feature.decoration() != null;
            boolean editable = placedObject && !BoardFeatures.terrainOwned(feature.decoration());
            // Both graphics modes use the same finished rail carrier, including road embedding and bridge grades.
            Model model = BoardRail.route(feature) ? routeModel(build, BoardRail.layout(scene, tile, feature, surface,
                        prepared.bridgeShapes()::get))
                  : placedObject ? assets.slotted(feature.asset())
                  : generatedIndustrial ? assets.industrial(BoardIndustrial.layout(scene, tile, feature))
                  : wallLayout != null ? assets.wall(wallLayout)
                  : multiHexLayout != null ? assets.multiHexLevels(multiHexLayout).getFirst()
                  : building != null ? building.model(0)
                  : feature.kind() == BoardScene.FeatureKind.TREE
                  ? assets.lodModel(feature.asset(), 0)
                  : assets.model(feature.asset(), BoardGroundCut.colours(tile, feature), BoardFeatures.label(tile, feature));
            assets.take(build.preview == null ? this : build.preview, building, model);
            java.util.function.UnaryOperator<Model> drawn = mesh ->
                  feature.kind() == BoardScene.FeatureKind.TREE || fungus || foliageModel(mesh) ? foliage(mesh) : mesh;
            ModelInstance instance = building != null ? building.instance(0)
                  : new ModelInstance(drawn.apply(model));
            boolean metal = feature.kind() == BoardScene.FeatureKind.METAL;
            // A single-model building or fuel tank (no kit assembly): a large occluder the depth pre-pass draws (prepass);
            // not kit modules, a wall, an industrial structure, a placed object or an interior floor.
            boolean shell = building == null && wallLayout == null && multiHexLayout == null && !placedObject && !generatedIndustrial
                  && feature.kind() != BoardScene.FeatureKind.INDUSTRIAL
                  && (feature.kind() == BoardScene.FeatureKind.BUILDING || feature.asset().startsWith("buildings/"));
            // Its renderables carry the colours to the instance data and the shader (copy keeps them).
            if (placedObject) { instance.userData = RigidGlb.packed(feature.colours()); }
            for (Material material : instance.materials) {
                GpuTrafficSignal.mark(feature.asset(), material);
                var finish = material.get(GpuModelMaterial.class, GpuModelMaterial.TYPE);
                if (shell && finish != null) { material.set(finish.occluder(true)); }
                if (building == null && !BoardWall.supports(feature.asset()) && material.id.equals("wall")) {
                    material.get(TextureAttribute.class, TextureAttribute.Diffuse).scaleV = feature.height();
                }
                if (material.id.startsWith("geyser-") || material.id.startsWith("rubble-")
                      || material.id.startsWith("fortified-")) {
                    // Terrain scenery shares the ground's lighting and weather response.
                    material.set(new Ground(.15f));
                    if (material.id.equals("geyser-lava")) {
                        Material lava = GpuMagmaShader.material(assets, GpuMagmaShader.LAVA, null);
                        if (lava != null) { for (Attribute attribute : lava) { material.set(attribute.copy()); } }
                    }
                }
            }
            BoundingBox bounds = instance.calculateBoundingBox(new BoundingBox());
            boolean coral = feature.asset().startsWith("mars/");
            // Coral colonies have wide, uneven mineral roots. Embed that root layer while preserving cover height.
            float rootInset = coral ? bounds.getDepth() * .06f : 0;
            float coralScale = coral ? feature.height() * BoardGeometry.level() / (bounds.getDepth() - rootInset) : 0;
            float plantingRadius = coral ? build.coralRootRadii.computeIfAbsent(model,
                  key -> coralRootRadius(key, bounds.min.z + rootInset)) * coralScale : BoardRelief.metres(.8f);
            float px = BoardGeometry.centerX(tile.coords()) + feature.x() * BoardGeometry.hexScale();
            float py = BoardGeometry.centerY(tile.coords()) + feature.y() * BoardGeometry.hexScale();
            if (feature.kind() == BoardScene.FeatureKind.TREE && !feature.authoredPlacement() || fungusScatter) {
                // A tree stands on its hex's own ground, never over a receding rim or a transition's slope.
                // Roadside cover keeps its captured route clearance instead of being pulled toward the carriageway.
                float[] spot = surface.relief.settle(px, py,
                      coral && tile.road() != BoardRoad.Kind.NONE ? BoardRelief.metres(.8f) : plantingRadius);
                px = spot[0];
                py = spot[1];
            }
            float base = ground.height(px, py);
            if (coral) {
                // Sample the drawn terrain around the roots; a high point must not suspend the rest of the base.
                for (int sample = 0; sample < 8; sample++) {
                    double angle = sample * Math.PI / 4;
                    float x = px + plantingRadius * (float) Math.cos(angle);
                    float y = py + plantingRadius * (float) Math.sin(angle);
                    var receiving = BoardGeometry.tile(scene, x, y);
                    var support = receiving == null ? surface : surfaces.getOrDefault(receiving.coords(), surface);
                    base = Math.min(base, support.height(x, y));
                }
                base -= (bounds.min.z + rootInset) * coralScale;
            }
            if (BoardRail.route(feature)) {
                instance.transform.setToTranslation(BoardGeometry.centerX(tile.coords()), BoardGeometry.centerY(tile.coords()),
                            BoardRail.level(tile, feature) * BoardGeometry.level())
                      .scale(BoardGeometry.hexScale(), BoardGeometry.hexScale(), BoardGeometry.hexScale());
            } else if (feature.decoration() != null) {
                var object = feature.decoration();
                float anchorX = BoardGeometry.centerX(tile.coords()) + (float) object.x() * BoardGeometry.width();
                float anchorY = BoardGeometry.centerY(tile.coords()) + (float) object.y() * BoardGeometry.height();
                // A held drag keeps its original owner until release, but already stands on its destination surface.
                var footprint = BoardGeometry.tile(scene, anchorX, anchorY);
                // Terrain-owned deck controls and rail stops stay on their owner's generated overhang.
                boolean ownerDeck = BoardFeatures.terrainOwned(object) && object.placement().receiver() != null
                      && object.placement().receiver().terrain().equals("bridge");
                Coords receiving = ownerDeck || footprint == null ? tile.coords() : footprint.coords();
                GroundView receivingGround = receiving.equals(tile.coords()) ? ground : ground(build, receiving);
                TileMesh receivingMesh = receiving.equals(tile.coords()) ? null : editorTile(receiving);
                var receivingBridge = receivingGround.deck();
                if (receivingBridge == null && receivingMesh != null) { receivingBridge = receivingMesh.bridgeShape; }
                float receiver = decorationHeight(scene, feature, receivingGround.height(anchorX, anchorY), receivingGround.faces(),
                      receivingBridge, receivingMesh == null ? supports : receivingMesh.props, anchorX, anchorY, scene.tile(receiving))
                      + feature.elevation() * BoardGeometry.level();
                if (!Float.isFinite(receiver)) { continue; }
                placeDecoration(instance, tile.coords(), feature, receiver);
            } else if (limb) {
                // Lay the held limb on its side, then ground its actual bounds. No unit/entity is created.
                float scale = feature.scale() * BoardGeometry.unitScale() * BoardGeometry.hexScale();
                instance.transform.setToTranslation(px, py, base).rotate(Vector3.Z, feature.rotation())
                      .rotate(Vector3.Y, 90).scale(scale, scale, scale);
                BoundingBox placed = new BoundingBox(bounds).mul(instance.transform);
                instance.transform.val[Matrix4.M03] += px - placed.getCenterX();
                instance.transform.val[Matrix4.M13] += py - placed.getCenterY();
                instance.transform.val[Matrix4.M23] += base - placed.min.z + .12f * BoardGeometry.hexScale();
            } else if (feature.authoredPlacement()) {
                // As import's receiver: on a bridge hex everything but trees stands on the deck. An earthwork stands on
                // its hex's ground as it was before it was dug.
                base = scenerySupport(BoardGroundCut.digs(feature.asset()) ? ground.undug() : ground, ground.deck(), supports,
                      BoardGeometry.centerX(tile.coords()), BoardGeometry.centerY(tile.coords()),
                      feature.kind() != BoardScene.FeatureKind.TREE && BoardBridge.feature(tile) != null
                            ? BoardBridge.deckZ(tile) : Float.NEGATIVE_INFINITY);
                placeAuthored(instance, px, py, base, BoardGroundCut.fitted(scene, tile, feature));
                BoardGroundCut.showParts(instance, feature.parts());
            } else {
                // Trees and structures retain inspectable proportions; their actual Z extent fits the rules' height.
                // Fuel tanks and industrial structures also use the building catalog, without building cutaways.
                boolean fitHeight = feature.kind() == BoardScene.FeatureKind.TREE
                      || fungus || metal || feature.kind() == BoardScene.FeatureKind.BUILDING || feature.asset().startsWith("buildings/");
                float sourceHeight = fitHeight ? bounds.getDepth() : 1;
                float verticalScale = coral ? coralScale
                      : building == null && wallLayout == null && multiHexLayout == null
                            ? feature.height() * BoardGeometry.level() / sourceHeight
                      : BoardGeometry.level() / GpuBuilding.LEVEL_HEIGHT;
                // Authored colonies and understory retain their proportions when fitted to the foliage height.
                float horizontalScale = fungus || coral
                      || feature.asset().startsWith("vegetation/foliage-") ? verticalScale
                      : feature.scale() * BoardGeometry.hexScale();
                instance.transform.setToTranslation(px, py,
                      base + feature.elevation() * BoardGeometry.level()
                            - (metal ? bounds.min.z * verticalScale + .02f * BoardGeometry.level() : 0))
                      .rotate(Vector3.Z, feature.rotation())
                      .scale(horizontalScale * (float) feature.stretch().x(), horizontalScale * (float) feature.stretch().y(),
                            verticalScale * (float) feature.stretch().z());
            }
            bounds.mul(instance.transform);
            if (metal && !BoardMetalDeposits.accepts(scene, tile, surface, feature)) { continue; }
            GpuGeysers.Emitter geyser = GpuGeysers.emitter(feature.asset(), instance.transform,
                  GpuGeysers.vent(instance.model));
            if (geyser != null) {
                chunk.geysers.add(geyser);
                geyserBounds(bounds, geyser);
            }
            if (fungusScatter && (surface.relief.obstructed(new Vector3(px, py, base),
                  Math.max(bounds.getWidth(), bounds.getHeight()) * .5f, bounds.getDepth())
                  || !surface.relief.visibleScatter(new Vector3(px, py, base), bounds.getDepth()))) { continue; }
            // A placed plant the instance data can hold (no tilt, mirror or uneven stretch) joins the tree stand, still
            // editable. The Tactical View keeps placed objects but plants, as it keeps no other plant.
            var object = feature.decoration();
            boolean standTree = object == null ? feature.kind() == BoardScene.FeatureKind.TREE || fungus
                  : foliageModel(model) && GpuTreeInstances.placeable(instance.transform);
            var prop = new Prop(tile.coords(), instance, bounds, standTree ? feature.asset() : null,
                  feature.kind() == BoardScene.FeatureKind.LIMB || metal, building,
                  object != null && !foliageModel(model) || feature.kind() == BoardScene.FeatureKind.BUILDING
                        || feature.asset().startsWith("buildings/"),
                  Float.NaN, feature.kind() == BoardScene.FeatureKind.INDUSTRIAL);
            prop.infrastructure = routeFeature(tile, feature);
            prop.scatter = feature.kind() == BoardScene.FeatureKind.SCATTER;
            // Tundra tussocks stand with the trees but, like the grass, are no cover: the Tactical View omits them.
            prop.cover = object == null && standTree && !prop.scatter && !feature.asset().equals(BoardScatter.TUSSOCK)
                  && (features == fitted || fitted.contains(feature));
            // Terrain-owned transport furniture stays with its shared road/rail geometry in every view. Keep its
            // normal instanced-object path so traffic-signal LOD swaps and blinking materials continue to update.
            prop.tactical = placedObject ? BoardFeatures.coverSkin(object)
                  || BoardFeatures.terrainOwned(object) && (feature.asset().startsWith("scenery/road/")
                        || feature.asset().equals("scenery/rail/buffer-stop")
                        || feature.asset().equals("scenery/rail/crossing-signal")) : metal || gameplayScenery(instance);
            // Placed objects, from either file format, under a quarter hex: zoomed-out views skip them (drawn).
            prop.small = object != null && !BoardRail.route(feature)
                  && !standTree && !foliageModel(model) && geyser == null
                  && bounds.getDimensions(new Vector3()).len() < TreeLod.SMALL_OBJECT * BoardGeometry.width();
            prop.decorationId = editable ? object.id() : null;
            if (placedObject) { prop.decorationFeature = feature; }
            prop.decorationAnchorZ = instance.transform.val[Matrix4.M23] - feature.elevation() * BoardGeometry.level();
            // A click on a maglev's pier selects its route (batch-10 answer 8): the beam's triangles and its piers'.
            // A placed object's simpler levels, shared meshes as its near one (a stand tree has the stand's levels).
            if (wallLayout != null) {
                prop.levels = assets.wallLevels(wallLayout);
            } else if (multiHexLayout != null) {
                prop.levels = assets.multiHexLevels(multiHexLayout);
            } else if (object != null && !standTree && !BoardRail.route(feature)) {
                List<Model> levels = assets.objectLevels(feature.asset());
                if (levels.stream().distinct().count() > 1) { prop.levels = levels.stream().map(drawn).toList(); }
            }
            prop.receiver = BoardFeatures.receiver(feature);
            chunk.props.add(prop);
            Model glass = multiHexLayout == null ? null : assets.multiHexGlass(multiHexLayout);
            if (glass != null) {
                var pane = new ModelInstance(glass);
                pane.transform.set(instance.transform);
                chunk.glass.add(pane);
                chunk.bounds.ext(pane.calculateBoundingBox(new BoundingBox()).mul(pane.transform));
            }
            if (feature.decoration() == null && (feature.kind() == BoardScene.FeatureKind.BUILDING
                  || feature.kind() == BoardScene.FeatureKind.INDUSTRIAL || feature.kind() == BoardScene.FeatureKind.FUEL_TANK
                  || feature.kind() == BoardScene.FeatureKind.PROP && !feature.asset().equals("vegetation/field"))) {
                supports.add(prop);
            }
            if (feature.kind() == BoardScene.FeatureKind.BUILDING && !wallBuilding && multiHexLayout == null) {
                Model interior = building == null ? assets.interior(feature.asset(), Math.round(feature.height()))
                      : building.interior();
                ModelInstance struts = new ModelInstance(interior, "struts");
                struts.transform.set(instance.transform);
                chunk.struts.add(new InteriorStruts(tile.coords(), struts));
                // Each floor borrows its existing mesh range but owns its opacity and opaque/transparent pass choice.
                for (int level = 0; level < Math.round(feature.height()); level++) {
                    ModelInstance floorInstance = new ModelInstance(interior, "floors");
                    var parts = floorInstance.nodes.first().parts;
                    String id = "floor-" + level;
                    for (int part = parts.size - 1; part >= 0; part--) {
                        if (!parts.get(part).meshPart.id.equals(id)) { parts.removeIndex(part); }
                    }
                    floorInstance.transform.set(instance.transform);
                    BoundingBox floorBounds = floorInstance.calculateBoundingBox(new BoundingBox()).mul(floorInstance.transform);
                    chunk.props.add(new Prop(tile.coords(), floorInstance, floorBounds, null, false, null, true,
                          floorBounds.min.z));
                }
            }
            chunk.bounds.ext(bounds);
        }
        if (build.preview == null) {
            placeDecals(build, tile, ground, supports);
            placeDecorationPaint(build, tile, ground.faces(), ground.deck(), supports);
        }
    }

    /**
     * The placement's support rule ({@link megamek.common.board.BoardDecoration.Placement#level}) settled on the drawn
     * meshes: the chosen support beneath the anchor; where that support has no surface there, its height at the
     * {@code owner} hex centre, as the legacy composition's shared plane did; a missing bridge support at its deck
     * plane ({@link BoardBridge#deckZ}); and only then the sampled ground. A rail rider
     * stands on the rail top the owner's track draws there ({@link BoardRail#railTop}).
     */
    private float decorationHeight(BoardScene scene, BoardScene.Feature feature, float groundHeight,
          List<BoardSurface.Face> ground, BoardBridge.Shape bridge, List<Prop> supports, float x, float y,
          BoardScene.Tile owner) {
        float level = BoardGeometry.level(), scale = BoardGeometry.hexScale();
        float railTop = owner == null ? 0 : BoardRail.railTop(scene, owner, feature,
              (x - BoardGeometry.centerX(owner.coords())) / scale, (y - BoardGeometry.centerY(owner.coords())) / scale) * scale;
        return railTop + (float) (feature.decoration().placement().level(groundHeight / level, receiver -> {
            float height = supportHeight(receiver, ground, bridge, supports, x, y);
            if (!Float.isFinite(height)) {
                height = supportHeight(receiver, ground, bridge, supports,
                      BoardGeometry.centerX(owner.coords()), BoardGeometry.centerY(owner.coords()));
            }
            if (!Float.isFinite(height) && receiver.equals("bridge") && BoardBridge.feature(owner) != null) {
                height = BoardBridge.deckZ(owner);
            }
            return (Float.isFinite(height) ? height : groundHeight) / level;
        }) * level);
    }

    /**
     * The highest surface of a bridge deck, ice sheet, roof, industrial top or fuel tank at (x, y), or negative infinity
     * where it has none.
     */
    private float supportHeight(String receiver, List<BoardSurface.Face> ground, BoardBridge.Shape bridge,
          List<Prop> supports, float x, float y) {
        float height = Float.NEGATIVE_INFINITY;
        if (receiver.equals("bridge")) {
            height = BoardBridge.top(bridge, x, y);
        } else if (receiver.equals("ice")) {
            for (var face : ground) {
                if (face.finish() == BoardSurface.Finish.ICE) { height = Math.max(height, face.height(x, y)); }
            }
        } else {
            var point = new Vector3();
            for (Prop support : supports) {
                if (!receiver.equals(support.receiver)) { continue; }
                var ray = new Ray(new Vector3(x, y, support.bounds.max.z + 1), new Vector3(0, 0, -1));
                if (hitProp(support, ray, point)) { height = Math.max(height, point.z); }
            }
        }
        return height;
    }

    private static void placeDecoration(ModelInstance instance, Coords coords, BoardScene.Feature feature, float z) {
        instance.transform.set(BoardFeatures.decorationTransform(coords, feature, z));
        // Restore authored culling when unmirroring; reflected winding needs both faces in every pass.
        for (Material material : instance.materials) { culling(instance, material, false); }
    }

    /**
     * A prop's culling on one of its materials, for every fade: its model's own, and both faces for a mirrored placement
     * drawn as it is (packed, one by one or faded), whose reflected winding needs them in every pass; faded, the faces
     * turned away (the front faces of a mirrored placement), so a see-through shell never shows its inside. An instanced
     * mirror image keeps its model's own culling ({@link Chunk#cacheDecorations}): its batch flips the front face.
     */
    private static void culling(ModelInstance instance, Material material, boolean faded) {
        culling(instance.model, material, GpuTreeInstances.mirrored(instance.transform), faded);
    }

    private static void culling(Model model, Material material, boolean mirrored, boolean faded) {
        Material own = model.getMaterial(material.id);
        Attribute cull = own == null ? null : own.get(IntAttribute.CullFace);
        if (faded) { material.set(IntAttribute.createCullFace(mirrored ? GL20.GL_FRONT : GL20.GL_BACK)); }
        else if (mirrored) { material.set(IntAttribute.createCullFace(GL20.GL_NONE)); }
        else if (cull != null) { material.set(cull.copy()); }
        else { material.remove(IntAttribute.CullFace); }
    }

    /** Include an animated plume in both the installed and temporary placement's culling envelope. */
    private static void geyserBounds(BoundingBox bounds, GpuGeysers.Emitter geyser) {
        float reach = 20 * geyser.scale();
        bounds.ext(geyser.origin().x - reach, geyser.origin().y - reach, geyser.origin().z);
        bounds.ext(geyser.origin().x + reach, geyser.origin().y + reach,
              geyser.origin().z + (geyser.active() ? 40 : 15) * geyser.scale());
    }

    private List<BoardSurface.Face> decorationReceivers(String receiver, List<BoardSurface.Face> ground,
          BoardBridge.Shape bridge, List<Prop> supports) {
        if (receiver.equals("ground") || receiver.equals("ice")) {
            boolean ice = receiver.equals("ice");
            return ground.stream().filter(f -> (f.finish() == BoardSurface.Finish.ICE) == ice && BoardDecals.upward(f)).toList();
        }
        List<BoardSurface.Face> faces = new ArrayList<>();
        if (receiver.equals("bridge") && bridge != null) {
            for (var facet : bridge.facets()) {
                if (facet.part() == BoardBridge.Part.TOP) { faces.add(new BoardSurface.Face(facet.a(), facet.b(), facet.c(), BoardSurface.Finish.TOP)); }
            }
        }
        // A flat built deck is its GLB prop, the hex's support with receiver "bridge".
        for (Prop prop : supports) {
            if (!receiver.equals(prop.receiver)) { continue; }
            faces.addAll(receiverFaces(prop));
        }
        return faces;
    }

    private void placeDecorationPaint(ChunkBuild build, BoardScene.Tile tile, List<BoardSurface.Face> ground,
          BoardBridge.Shape bridge, List<Prop> supports) {
        var paint = build.prepared.paint().getOrDefault(tile.coords(), List.of());
        for (var stamp : paint) {
            var object = stamp.object();
            Texture texture = assets.decorationPaint(object.asset());
            if (texture == null) { continue; }
            var faces = paintFaces(stamp.owner(), object, tile.coords(), ground, bridge, supports);
            build.chunk.tileMeshes.get(tile.coords()).paint.add(new PaintedDecal(stamp, faces, paintLift()));
            var uv = BoardDecals.paintUv(object);
            // An elevator's platform is painted diamond plate (BoardElevator), as long as the decal lies square on it.
            boolean plate = BoardFeatures.shaft(tile) && !build.chunk.columns && uv.uy() == 0 && uv.vx() == 0;
            Material material = plate ? platePaint(texture, stamp.owner(), object, uv) : material(texture, true);
            // Paint shares a support plane, writes no depth, and blends in explicit painter order.
            material.set(new GpuDecalOrder(stamp));
            // Both camera presentations draw this one mesh; legacy terrain art retains its separate layers.
            build.layers.get(6).addTriangles(material, meshes -> {
                if (plate) { plateTriangles(meshes, object, faces); } else { paintTriangles(meshes, stamp.owner(), object, faces); }
            });
        }
    }

    /**
     * The faces of the decal {@code object} of {@code owner} on {@code recipient}: projected on its receiver's faces there
     * ({@link #decorationReceivers}). The one paint composition of a chunk ({@link #placeDecorationPaint}) and of the
     * editor's ghost ({@link #decalGhost}), both drawn by {@link #paintTriangles}.
     */
    private List<BoardSurface.Face> paintFaces(Coords owner, megamek.common.board.BoardDecoration object,
          Coords recipient, List<BoardSurface.Face> ground, BoardBridge.Shape bridge, List<Prop> supports) {
        var receivers = decorationReceivers(object.placement().receiver().terrain(), ground, bridge, supports);
        return BoardDecals.project(owner, recipient, receivers, object);
    }

    /** Paint lies this far above the faces it is projected on, drawn and picked. */
    private static float paintLift() { return (BoardDecals.LIFT + .02f) * BoardGeometry.hexScale(); }

    /** The triangles of the decal {@code object} of {@code owner} on its paint {@code faces} ({@link #paintFaces}). */
    private static void paintTriangles(Supplier<MeshPartBuilder> meshes, Coords owner,
          megamek.common.board.BoardDecoration object, List<BoardSurface.Face> faces) {
        var uv = BoardDecals.paintUv(object);
        float lift = paintLift();
        for (var face : faces) {
            var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
            meshes.get().triangle(decorationVertex(face.a(), owner, object, uv, normal, lift),
                  decorationVertex(face.b(), owner, object, uv, normal, lift),
                  decorationVertex(face.c(), owner, object, uv, normal, lift));
        }
    }

    private static MeshPartBuilder.VertexInfo decorationVertex(Vector3 point, Coords coords,
          megamek.common.board.BoardDecoration object, BoardDecals.PaintUv uv, Vector3 normal, float lift) {
        double dx = point.x - BoardGeometry.centerX(coords) - object.x() * BoardGeometry.width();
        double dy = point.y - BoardGeometry.centerY(coords) - object.y() * BoardGeometry.height();
        // The decal's paint colour (slot 0, as lettering is painted) multiplies its image; none keeps the image's own.
        String paint = object.colours().slot(0);
        return vertex(new Vector3(point).add(0, 0, lift), normal, uv.u(dx, dy), uv.v(dx, dy),
              paint == null ? Color.WHITE : Color.valueOf(paint));
    }

    /** A prop's upward faces; a deck receives on its road surface alone, never on its rails' or truss chords' tops. */
    private List<BoardSurface.Face> receiverFaces(Prop prop) {
        List<BoardSurface.Face> faces = new ArrayList<>();
        List<Vector3> points = triangles(prop.instance().nodes,
              "bridge".equals(prop.receiver) ? part -> part.material.id.equals("bridge-deck") : part -> true);
        for (int i = 0; i < points.size(); i += 3) {
            var face = new BoardSurface.Face(points.get(i).mul(prop.instance().transform),
                  points.get(i + 1).mul(prop.instance().transform), points.get(i + 2).mul(prop.instance().transform),
                  BoardSurface.Finish.TOP);
            if (BoardDecals.upward(face)) { faces.add(face); }
        }
        return faces;
    }

    /**
     * Project after supports are installed: an Enhanced chunk's legacy paint on its surface (layer 4), a Classic chunk's
     * tileset decals and object sprites on its column (layer 5), each on the structures and the bridge deck over it.
     */
    private void placeDecals(ChunkBuild build, BoardScene.Tile tile, GroundView ground, List<Prop> supports) {
        boolean original = build.chunk.columns;
        if (original ? tile.tilesetDecals() == null && tile.tilesetScenery() == null : BoardDecals.legacyPaint(tile) == null) {
            return;
        }
        List<BoardSurface.Face> receivers = new ArrayList<>();
        for (Prop prop : supports) { receivers.addAll(receiverFaces(prop)); }
        var bridge = ground.deck();
        if (bridge != null) {
            for (var facet : bridge.facets()) {
                if (facet.part() != BoardBridge.Part.TOP) { continue; }
                receivers.add(new BoardSurface.Face(new Vector3(facet.a()), new Vector3(facet.b()), new Vector3(facet.c()),
                      BoardSurface.Finish.TOP));
            }
        }
        List<BoardSurface.Face> faces = original ? ground.faces() : ground.faces().stream()
              .filter(face -> !tile.frozen() || !tile.water() || face.finish() == BoardSurface.Finish.ICE).toList();
        if (!original || tile.tilesetDecals() != null) {
            List<BoardSurface.Face> projected = BoardDecals.project(tile.coords(), faces, receivers);
            TextureRegion art = decals.region(new DecalSlot(tile.coords(), original ? 1 : 0));
            build.layers.get(original ? 5 : 4).addTriangles(material(art.getTexture(), true), meshes -> {
                for (var face : projected) {
                    surface(meshes.get(), tile.coords(), face, art, BoardDecals.LIFT * BoardGeometry.hexScale());
                }
            });
            for (var face : projected) { build.chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
        }
        if (original && tile.tilesetScenery() != null) {
            // A sprite represents a rigid object, just like its 3D replacement. Keep its overhang at its anchor.
            Vector3 center = BoardGeometry.center(tile.coords(), 0);
            center.z = Float.NEGATIVE_INFINITY;
            for (var surfaces : List.of(faces, receivers)) {
                for (var face : surfaces) { center.z = Math.max(center.z, face.height(center.x, center.y)); }
            }
            TextureRegion art = decals.region(new DecalSlot(tile.coords(), 2));
            build.layers.get(5).add(material(art.getTexture(), true), mesh -> {
                for (int edge = 0; edge < 6; edge++) {
                    Vector3 a = BoardGeometry.corner(tile.coords(), 0, edge), b = BoardGeometry.corner(tile.coords(), 0, edge + 1);
                    a.z = b.z = center.z;
                    surface(mesh, tile.coords(), new BoardSurface.Face(center, a, b, BoardSurface.Finish.TOP),
                          art, BoardDecals.LIFT * BoardGeometry.hexScale());
                }
            });
            build.chunk.bounds.ext(center);
        }
    }

    /**
     * Drop onto the highest solid at the anchor. Water is never a support; roofs/decks use their drawn meshes, and where
     * the deck has no facet there (a flat built deck, which its GLB draws), {@code deck}, its deck plane.
     */
    private float scenerySupport(GroundView ground, BoardBridge.Shape bridge, List<Prop> supports, float x, float y,
          float deck) {
        float height = ground.height(x, y);
        for (var face : ground.faces()) {
            if (face.finish() == BoardSurface.Finish.ICE) { height = Math.max(height, face.height(x, y)); }
        }
        var ray = new Ray(new Vector3(x, y, height + 1), new Vector3(0, 0, -1));
        var point = new Vector3();
        float distance = Float.POSITIVE_INFINITY;
        if (bridge != null) {
            ray.origin.z = Math.max(height, bridge.bounds().max.z) + 1;
            distance = bridge.hit(ray);
            if (Float.isFinite(distance)) { height = Math.max(height, ray.origin.z - (float) Math.sqrt(distance)); }
        }
        if (!Float.isFinite(distance)) { height = Math.max(height, deck); }
        for (Prop prop : supports) {
            ray.origin.z = Math.max(height, prop.bounds().max.z) + 1;
            if (hitProp(prop, ray, point)) { height = Math.max(height, point.z); }
        }
        return height;
    }

    /** The authored root layer determines clearance; upper fans and branches may overhang a narrow terrace. */
    private static float coralRootRadius(Model model, float rootTop) {
        float radiusSquared = 0;
        for (Mesh mesh : model.meshes) {
            int stride = mesh.getVertexSize() / Float.BYTES;
            int position = mesh.getVertexAttribute(VertexAttributes.Usage.Position).offset / Float.BYTES;
            float[] vertices = new float[mesh.getNumVertices() * stride];
            mesh.getVertices(vertices);
            for (int i = position; i < vertices.length; i += stride) {
                if (vertices[i + 2] <= rootTop) {
                    radiusSquared = Math.max(radiusSquared, vertices[i] * vertices[i] + vertices[i + 1] * vertices[i + 1]);
                }
            }
        }
        return (float) Math.sqrt(radiusSquared);
    }

    private final class ChunkBuild {
        EditorRoutes preview;
        final Chunk chunk = new Chunk();
        final Chunk source;
        final BoundingBox bounds = new BoundingBox().inf();
        float scatterDiameter;
        final BoardScene scene;
        final Prepared prepared;
        final TerrainLoadProgress progress;
        final int x, y, width, height;
        final float floor;
        final List<GpuTerrainLayer> layers = List.of(new GpuTerrainLayer(), new GpuTerrainLayer(),
              new GpuTerrainLayer(), new GpuTerrainLayer(), new GpuTerrainLayer(), new GpuTerrainLayer(),
              new GpuTerrainLayer());
        Material scatterMaterial;
        // Derived once per model while preparing a chunk; no extra model data survives construction.
        final Map<Model, Float> coralRootRadii = new IdentityHashMap<>();
        final Map<String, LiquidSurface> animations = new HashMap<>();
        final Map<Material, Material> reusedMaterials = new java.util.IdentityHashMap<>();
        List<MeshBatch> meshes;
        /** Whether the chunk's relief is cliffs (cliffWalls), measured where its meshes are prepared. */
        boolean cliffs;
        int collected, uploadLayer, uploadBuffer;

        ChunkBuild(BoardScene scene, int x, int y, float floor, TerrainLod lod, boolean columns, boolean tessellated,
              Prepared prepared, Chunk source, TerrainLoadProgress progress) {
            this.scene = scene;
            this.source = source;
            this.x = x;
            this.y = y;
            this.floor = floor;
            this.prepared = prepared;
            this.progress = progress;
            width = Math.min(CHUNK_SIZE, scene.width() - x);
            height = Math.min(CHUNK_SIZE, scene.height() - y);
            chunk.lod = lod;
            chunk.columns = columns;
            chunk.tessellated = tessellated;
            chunk.charts = new GpuMaterialCache.Charts(scene, x, y);
            chunk.charts.keep(source == null ? null : source.charts, prepared.reused());
            chunk.paintOnlyUpdate = prepared.paintOnly();
            if (chunk.paintOnlyUpdate) { progress.begin("paint", width * height); return; }
            Map<BoardScene.Pixels, BoardScene.Pixels> masks = new HashMap<>();
            prepared.roads().values().forEach(patches -> patches.forEach(p -> masks.put(p.mask().pixels(), p.mask().pixels())));
            if (source != null) {
                for (Coords coords : prepared.reused()) {
                    for (TileRange range : source.tileMeshes.get(coords).ranges) {
                        var mask = range.material().get(GpuRoads.Mask.class, GpuRoads.Mask.TYPE);
                        if (mask != null) { masks.put(mask.data.pixels(), mask.data.pixels()); }
                    }
                }
            }
            try {
                chunk.waterField = prepared.water() == null ? null : prepared.water().upload();
                progress.advance();
                chunk.lavaField = prepared.lava() == null ? null : prepared.lava().upload(false);
                progress.advance();
                chunk.roadMasks.update(masks);
                progress.advance();
            } catch (RuntimeException | Error failure) {
                chunk.dispose();
                throw failure;
            }
            if (source != null) {
                for (LiquidSurface animation : source.liquidMaterials) { animations.put(animation.material().id, animation); }
            }
            progress.begin("models", width * height);
        }

        boolean collectUntil(long deadline) {
            do {
                Coords coords = new Coords(x + collected / height, y + collected % height);
                layers.forEach(layer -> layer.owner = coords);
                TileMesh tile = new TileMesh();
                chunk.tileMeshes.put(coords, tile);
                if (chunk.paintOnlyUpdate) {
                    TileMesh previous = source.tileMeshes.get(coords);
                    var support = prepared.topography().get(coords);
                    placeDecorationPaint(this, scene.tile(coords), support == null ? List.of() : support.faces(), previous.bridgeShape, previous.props);
                    // A repainted hex grew again (preparePaint); installPaint hands its plants over with the paint.
                    tile.plants = prepared.plants().containsKey(coords) ? prepared.plants().get(coords) : previous.plants;
                    tile.bounds.set(previous.bounds);
                } else if (prepared.reused().contains(coords)) {
                    TileMesh previous = source.tileMeshes.get(coords);
                    tile.support = previous.support;
                    // A reused hex with prepared support is one whose paint changed: it grew again on that support.
                    tile.plants = prepared.topography().containsKey(coords) ? prepared.plants().get(coords) : previous.plants;
                    tile.bridgeShape = previous.bridgeShape;
                    boolean repaint = !previous.paint.stream().map(PaintedDecal::stamp).toList()
                          .equals(prepared.paint().getOrDefault(coords, List.of()));
                    if (!repaint) { tile.paint.addAll(previous.paint); }
                    for (TileRange range : previous.ranges) {
                        if (repaint && range.material().has(GpuDecalOrder.TYPE)) { continue; }
                        Material material = reusedMaterials.computeIfAbsent(range.material(), original -> {
                            Material copy = new Material(original);
                            GpuWaterShader water = copy.get(GpuWaterShader.class, GpuWaterShader.TYPE);
                            if (water != null) { copy.set(water.withField(chunk.waterField)); }
                            GpuMagmaShader magma = copy.get(GpuMagmaShader.class, GpuMagmaShader.TYPE);
                            if (magma != null) { copy.set(magma.withField(chunk.lavaField)); }
                            var mask = copy.get(GpuRoads.Mask.class, GpuRoads.Mask.TYPE);
                            if (mask != null) { copy.set(mask.withRegion(chunk.roadMasks.region(mask.data.pixels()))); }
                            return copy;
                        });
                        layers.get(range.layer()).reuse(range, material);
                    }
                    // Instances have mutable fading/LoD state, while their authored models remain asset-owned.
                    for (Prop prop : previous.props) {
                        Prop copy = new Prop(coords, copy(prop.instance), prop.bounds,
                              prop.treeAsset, prop.hardSurface, prop.building, prop.structure, prop.floorZ, prop.industrial);
                        copy.scatter = prop.scatter;
                        copy.infrastructure = prop.infrastructure;
                        copy.tactical = prop.tactical;
                        copy.small = prop.small;
                        copy.decorationId = prop.decorationId;
                        copy.decorationFeature = prop.decorationFeature;
                        copy.decorationAnchorZ = prop.decorationAnchorZ;
                        copy.receiver = prop.receiver;
                        // The copy draws the level the original drew, and still picks the near level.
                        copy.pickingModel = prop.pickingModel;
                        copy.pickingTriangles = prop.pickingTriangles;
                        copy.levels = prop.levels;
                        copy.lod = prop.lod;
                        tile.props.add(copy);
                    }
                    for (InteriorStruts strut : previous.struts) {
                        tile.struts.add(new InteriorStruts(coords, new ModelInstance(strut.instance)));
                    }
                    for (ModelInstance pane : previous.glass) { tile.glass.add(copy(pane)); }
                    chunk.props.addAll(tile.props);
                    chunk.struts.addAll(tile.struts);
                    chunk.glass.addAll(tile.glass);
                    if (repaint) {
                        var support = prepared.topography().get(coords);
                        placeDecorationPaint(this, scene.tile(coords), support == null ? List.of() : support.faces(), tile.bridgeShape, tile.props);
                    }
                    tile.water = previous.water;
                    tile.bounds.set(previous.bounds);
                    tile.scatterDiameter = previous.scatterDiameter;
                } else {
                    chunk.bounds.inf();
                    chunk.scatterDiameter = 0;
                    int props = chunk.props.size(), struts = chunk.struts.size(), glass = chunk.glass.size();
                    collectTile(this, scene.tile(coords));
                    // A Classic hex's support is its column, which has no separate water geometry.
                    tile.support = prepared.topography().get(coords);
                    tile.water = !chunk.columns && scene.tile(coords).liquid().present()
                          ? prepared.surfaces().get(coords).waterGeometry() : null;
                    tile.plants = prepared.plants().get(coords);
                    tile.props.addAll(chunk.props.subList(props, chunk.props.size()));
                    tile.struts.addAll(chunk.struts.subList(struts, chunk.struts.size()));
                    tile.glass.addAll(chunk.glass.subList(glass, chunk.glass.size()));
                    tile.bounds.set(chunk.bounds);
                    tile.scatterDiameter = chunk.scatterDiameter;
                }
                bounds.ext(tile.bounds);
                scatterDiameter = Math.max(scatterDiameter, tile.scatterDiameter);
                collected++;
                progress.advance();
            } while (collected < width * height && System.nanoTime() < deadline);
            if (collected == width * height) {
                progress.begin("meshes", layers.stream().mapToInt(layer -> layer.geometry.values().stream()
                      .mapToInt(List::size).sum()).sum());
            }
            return collected == width * height;
        }

        void prepare(Runnable check) {
            meshes = layers.stream().map(layer -> layer.prepare(check, progress::advance)).toList();
            // A hex's area: odd-q columns stand three quarters of a hex's width apart.
            cliffs = meshes.getFirst().wallArea(scene.width(), scene.height())
                  > cliffWalls * width * height * .75f * BoardGeometry.width() * BoardGeometry.height();
            progress.begin("upload", meshes.stream().mapToInt(mesh -> mesh.buffers.size()).sum());
        }

        boolean uploadUntil(long deadline) {
            List<List<ModelInstance>> targets = List.of(chunk.opaque, chunk.scatter, chunk.overlays, chunk.water,
                  chunk.surfaceDecals, chunk.tilesetDecals, chunk.authoredPaint);
            while (uploadLayer < meshes.size()) {
                MeshBatch batch = meshes.get(uploadLayer);
                while (uploadBuffer < batch.buffers.size()) {
                    batch.upload(batch.buffers.get(uploadBuffer++), targets.get(uploadLayer), chunk.tileMeshes, uploadLayer);
                    progress.advance();
                    if (System.nanoTime() >= deadline) { return false; }
                }
                uploadBuffer = 0;
                uploadLayer++;
            }
            progress.begin("finishing", 1);
            return true;
        }

        Chunk finish() {
            chunk.bounds.set(bounds);
            chunk.scatterDiameter = scatterDiameter;
            if (chunk.paintOnlyUpdate) { progress.advance(); return chunk; }
            // ModelInstance copied its materials: a cliff chunk marks its own relief before anything keeps them.
            for (ModelInstance instance : cliffs ? chunk.opaque : List.<ModelInstance>of()) {
                for (Material material : instance.materials) {
                    if (cliffRelief(material)) {
                        material.set(new Ground(material.get(Ground.class, Ground.TYPE).value, true));
                    }
                }
            }
            chunk.terrainRenderables = GpuTerrainDepth.snapshot(chunk.opaque);
            // Cache tops and charted walls draw on terrain pages of their own (topPages): their draws switch programs.
            chunk.topRenderables.clear();
            chunk.topBounds.inf();
            chunk.wallWorld.inf();
            chunk.wallBounds.inf();
            for (int i = chunk.terrainRenderables.size - 1; i >= 0; i--) {
                Renderable part = chunk.terrainRenderables.get(i);
                boolean wall = GpuMaterialCache.isWall(part.material);
                if (!wall && !GpuMaterialCache.isTop(part.material)) { continue; }
                chunk.terrainRenderables.removeIndex(i);
                chunk.topRenderables.add(part);
                if (part.meshPart.size > 0) {
                    (wall ? chunk.wallWorld : chunk.topBounds).ext(part.meshPart.mesh.calculateBoundingBox(
                          new BoundingBox(), part.meshPart.offset, part.meshPart.size).mul(part.worldTransform));
                }
            }
            chunk.topRenderables.reverse();
            // Walls follow the chunk's detail (detailTops), reused hexes' too; their pages are their slots' (charts).
            if (chunk.wallWorld.isValid()) {
                chunk.wallBounds.set(chunk.charts.bounds);
                chunk.detailTops = true;
            }
            chunk.charts.built();
            chunk.scatterRenderables = GpuTerrainDepth.snapshot(chunk.scatter);
            chunk.waterRenderables = GpuTerrainDepth.snapshot(chunk.water);
            chunk.beyondRenderables.clear();
            for (Renderable part : chunk.waterRenderables) {
                Material beyond = GpuWaterPages.beyondMaterial(part.material);
                if (beyond == null) { continue; }
                Renderable copy = new Renderable().set(part);
                copy.material = beyond;
                chunk.beyondRenderables.add(copy);
            }
            // Ground, water and magma draw these materials as they are. An editor also retains the other actual parts,
            // including grass: palette cards may draw any of them after the loading stage.
            if (!chunk.topRenderables.isEmpty() && !cacheTops && cachesGround()) {
                // Their cached-lit and fill programs link in the warm-up too.
                cacheTops = true;
                terrainMaterialsReady = false;
            }
            for (Array<Renderable> layer : List.of(chunk.terrainRenderables, chunk.topRenderables,
                  chunk.scatterRenderables, chunk.waterRenderables, chunk.beyondRenderables)) {
                for (Renderable part : layer) {
                    if (editable || part.material.has(Sculpt.TYPE) || part.material.has(Pit.TYPE)
                          || part.material.has(GpuBackdrop.TYPE)
                          || part.material.has(GpuIceShader.TYPE) || part.material.has(Ground.TYPE)
                          || part.material.has(GpuWaterShader.TYPE) || part.material.has(GpuMagmaShader.TYPE)) {
                        prepareTerrainMaterial(part.material, part.meshPart.mesh.getVertexAttributes());
                    }
                }
            }
            // Built for tessellation, a road's coat curves with its ground once the chunk refines to full detail: its
            // curving copy links now too (prepareTerrainMaterial), as the editor's overlays do below.
            if (tessellation && !editable) {
                for (Renderable part : GpuTerrainDepth.snapshot(chunk.overlays)) {
                    if (part.material.has(GpuRoads.Mask.TYPE)) {
                        prepareTerrainMaterial(part.material, part.meshPart.mesh.getVertexAttributes());
                    }
                }
            }
            // The ground switches to family programs as they become ready; scatter and water keep generic ones.
            Array<Renderable> opaqueParts = new Array<>(chunk.terrainRenderables);
            opaqueParts.addAll(chunk.topRenderables);
            for (Renderable part : opaqueParts) {
                // A wall's direct draws take the program of its layout without the chart (GpuMaterialCache.uncharted).
                familyPrograms.request(part.material, part.meshPart.size,
                      GpuMaterialCache.uncharted(part.meshPart.mesh.getVertexAttributes()));
                // Built for tessellation, the curving copy draws once its chunk refines to full detail: its family
                // program builds in the loading stage's session too, from a copy kept for it, as its other programs
                // link then (prepareTerrainMaterial).
                if (familySession && chunk.tessellated && curvable(part.material)) {
                    Material copy = GpuTessellation.curvedCopy(part.material);
                    curvedFamilies.add(copy);
                    familyPrograms.request(copy, part.meshPart.size,
                          part.material.has(GpuRoads.Mask.TYPE) ? GpuRoads.TESSELLATED_VERTICES
                                : GpuTessellation.contacts(part.meshPart.mesh.getVertexAttributes()));
                }
            }
            chunk.depthTerrain = new GpuTerrainDepth(chunk.opaque);
            if (chunk.waterField != null) { chunk.waterField.finish(); }
            if (chunk.waterField != null) {
                float waveMargin = BoardRelief.metres(4);
                Vector3 low = new Vector3(chunk.bounds.min).sub(waveMargin, waveMargin, waveMargin);
                Vector3 high = new Vector3(chunk.bounds.max).add(waveMargin, waveMargin, waveMargin);
                chunk.bounds.ext(low).ext(high);
            }
            if (chunk.lavaField != null) { chunk.lavaField.finish(); }
            // ModelInstance copies materials; animate those owned by the rendered instances.
            for (List<ModelInstance> layer : List.of(chunk.opaque, chunk.water)) {
                for (ModelInstance instance : layer) {
                    for (Material material : instance.materials) {
                        LiquidSurface animation = animations.get(material.id);
                        if (animation != null) {
                            chunk.liquidMaterials.add(new LiquidSurface(material, animation.source(), animation.falling(), animation.current()));
                        }
                    }
                }
            }
            Map<Coords, BoundingBox> fungalHexes = new LinkedHashMap<>();
            for (Prop prop : chunk.props) {
                if (prop.decorationId != null) { chunk.objectProps.computeIfAbsent(prop.decorationId, id -> new ArrayList<>(1)).add(prop); }
                if (prop.building != null && prop.building.interior() != null) { chunk.sharedInteriors.add(prop.building.interior()); }
                if (prop.tree()) {
                    chunk.treeDiameter = Math.max(chunk.treeDiameter, prop.treeDiameter);
                    // Plain trees fill the stand below (restand); scatter keeps its own stand.
                    if (prop.scatter) {
                        chunk.scatterDiameter = Math.max(chunk.scatterDiameter, prop.treeDiameter);
                        chunk.scatterStand.add(prop.treeAsset, prop.instance().transform);
                    }
                    var glow = GpuFungus.light(scene.height(), prop.coords(), prop.treeAsset, prop.bounds());
                    if (glow != null) { chunk.fungalLights.add(glow); }
                    GpuFungus.Emitter emitter = GpuFungus.emitter(prop.treeAsset, prop.bounds());
                    if (emitter != null) {
                        chunk.spores.add(emitter);
                        fungalHexes.computeIfAbsent(prop.coords(), at -> new BoundingBox().inf()).ext(prop.bounds());
                    }
                } else {
                    chunk.cutaways.add(prop);
                }
            }
            fungalHexes.forEach((at, colony) -> chunk.spores.add(GpuFungus.cloud(at, colony)));
            chunk.restand();
            // With the depth pre-pass, or in the editor, the trees' programs and twins also build behind the loading stage.
            if (editable || depthPrepass.contains(Prepass.FOLIAGE) || depthPrepass.contains(Prepass.IMPOSTORS)) {
                for (GpuTreeInstances.Stand stand : List.of(chunk.stand, chunk.scatterStand)) {
                    for (String species : stand.trees.keySet()) {
                        if (standSpecies.add(species)) {
                            trees.speciesParts(species, (material, layout) -> prepareTerrainMaterial(material, layout));
                        }
                    }
                }
            }
            chunk.cacheProps();
            // Building shells' programs and those programs' twins link behind the loading stage (Prepass.OBJECTS).
            for (Renderable part : chunk.shadowPropRenderables) {
                if (prepass(part.material) == Prepass.OBJECTS) {
                    prepareTerrainMaterial(part.material, part.meshPart.mesh.getVertexAttributes());
                }
            }
            // Multi-hex buildings' glass draws blended by its .tres: its program links behind the loading stage too.
            for (Renderable part : GpuTerrainDepth.snapshot(chunk.glass)) {
                prepareTerrainMaterial(part.material, part.meshPart.mesh.getVertexAttributes());
            }
            // In the editor the water's depth pass and the roads', shores', decals', paint's and objects' programs
            // link before a stroke shows them too (advanceBuild): instanced parts as their batches draw them.
            if (editable) {
                for (Renderable part : chunk.waterRenderables) {
                    Material depth = GpuWaterPages.depthMaterial(part.material);
                    if (depth != null) { prepareTerrainMaterial(depth, part.meshPart.mesh.getVertexAttributes()); }
                    // A palette sample is a one-hex board, so its water exposes a cut even when this board does not.
                    if (waterMode(part.material) == GpuWaterShader.Mode.SURFACE) {
                        Material cut = GpuWaterPages.cutMaterial(part.material);
                        prepareTerrainMaterial(cut, part.meshPart.mesh.getVertexAttributes());
                        prepareTerrainMaterial(GpuWaterPages.beyondMaterial(cut), part.meshPart.mesh.getVertexAttributes());
                    }
                }
                List<Array<Renderable>> layers = new ArrayList<>(List.of(chunk.shadowPropRenderables, chunk.looseDecorations,
                      chunk.routeRenderables));
                for (List<ModelInstance> layer : List.of(chunk.overlays, chunk.surfaceDecals, chunk.tilesetDecals, chunk.authoredPaint)) {
                    layers.add(GpuTerrainDepth.snapshot(layer));
                }
                for (Array<Renderable> layer : layers) {
                    for (Renderable part : layer) { prepareTerrainMaterial(part.material, part.meshPart.mesh.getVertexAttributes()); }
                }
                for (Array<Renderable> layer : List.of(chunk.sharedProps, chunk.decorationRenderables)) {
                    for (Renderable part : layer) {
                        prepareTerrainMaterial(GpuTreeInstances.instancedMaterial(part.material),
                              part.meshPart.mesh.getVertexAttributes());
                    }
                }
            }
            // Combat scars mark the 3D relief and what stands on it; the Classic columns show none. A scar patch draws
            // with its receiver's plain program (GpuSurfaceScars), which an instanced object never draws with itself.
            if (combatScars && !chunk.columns) {
                for (var layer : List.of(chunk.sharedProps, chunk.propRenderables, chunk.decorationRenderables)) {
                    for (Renderable part : layer) {
                        if (instancedShader(part.material) && scarReceiver(part)) {
                            prepareTerrainMaterial(part.material, part.meshPart.mesh.getVertexAttributes());
                        }
                    }
                }
            }
            // Its props show these tiles' placements; an editor preview applies only later changes.
            chunk.editorTiles = scene.tiles();
            progress.advance();
            detailPixelsPerUnit = Float.NaN;
            return chunk;
        }
    }

    /**
     * Finished rendering triangles, also used to drape deployment borders and other native overlays: the 3D surface, or
     * the Classic column.
     */
    BoardTacticalGeometry.Surface tacticalSurface(Coords coords) {
        if (coverScene == null || coverScene.tile(coords) == null) { return null; }
        Chunk chunk = chunks.get(coords.getX() / CHUNK_SIZE * chunkRows + coords.getY() / CHUNK_SIZE);
        return installedSettings.call(() -> tacticalSurface(coverScene, chunk, coords, floor));
    }

    /** Only nearby installed receivers are read; stamps never scan or rebuild the board's terrain. */
    private List<GpuSurfaceScars.Source> scarSources(Vector3 point, float radius) {
        List<GpuSurfaceScars.Source> result = new ArrayList<>();
        int cx = (int) Math.floor(point.x / (BoardGeometry.width() * .75f));
        int cy = (int) Math.floor(-point.y / BoardGeometry.height());
        int reach = 1 + (int) Math.ceil(radius / Math.min(BoardGeometry.width() * .75f, BoardGeometry.height()));
        for (int x = Math.max(0, cx - reach); x <= Math.min(coverScene.width() - 1, cx + reach); x++) {
            for (int y = Math.max(0, cy - reach); y <= Math.min(coverScene.height() - 1, cy + reach); y++) {
                var coords = new Coords(x, y);
                var tile = chunks.get(x / CHUNK_SIZE * chunkRows + y / CHUNK_SIZE).tileMeshes.get(coords);
                if (tile == null || tile.bounds.max.x < point.x - radius || tile.bounds.min.x > point.x + radius
                      || tile.bounds.max.y < point.y - radius || tile.bounds.min.y > point.y + radius
                      || tile.bounds.max.z < point.z - radius || tile.bounds.min.z > point.z + radius) { continue; }
                result.addAll(scarSources(new GpuSurfaceScars.Receiver(coords, -1, -1, -1)));
                for (int index = 0; index < tile.props.size(); index++) {
                    Prop prop = tile.props.get(index);
                    if (prop.tree() || prop.scatter || prop.bounds().max.x < point.x - radius || prop.bounds().min.x > point.x + radius
                          || prop.bounds().max.y < point.y - radius || prop.bounds().min.y > point.y + radius
                          || prop.bounds().max.z < point.z - radius || prop.bounds().min.z > point.z + radius) { continue; }
                    result.addAll(scarSources(new GpuSurfaceScars.Receiver(coords, -1, index, -1)));
                }
            }
        }
        return result;
    }

    private List<GpuSurfaceScars.Source> scarSources(GpuSurfaceScars.Receiver receiver) {
        List<GpuSurfaceScars.Source> result = new ArrayList<>();
        var coords = receiver.coords();
        if (coverScene == null || coverScene.tile(coords) == null) { return result; }
        var tile = chunks.get(coords.getX() / CHUNK_SIZE * chunkRows + coords.getY() / CHUNK_SIZE).tileMeshes.get(coords);
        if (tile == null) { return result; }
        if (receiver.prop() < 0) {
            for (int index = 0; index < tile.ranges.size(); index++) {
                var range = tile.ranges.get(index);
                if (range.layer() != 0 || !range.material().has(Sculpt.TYPE) || !drawsScars(range.material())) {
                    continue;
                }
                var view = new Renderable();
                view.material = range.material();
                view.meshPart.set("scar-receiver", range.mesh(), range.offset(), range.count(), GL20.GL_TRIANGLES);
                result.add(new GpuSurfaceScars.Source(new GpuSurfaceScars.Receiver(coords, index, -1, 0), view));
            }
        } else {
            if (receiver.prop() >= tile.props.size()) { return result; }
            Prop prop = tile.props.get(receiver.prop());
            if (prop.tree() || prop.scatter) { return result; }
            var parts = GpuTerrainDepth.snapshot(List.of(prop.hoverOpaque == null ? prop.instance() : prop.hoverOpaque));
            for (int index = 0; index < parts.size; index++) {
                var source = scarSource(new GpuSurfaceScars.Receiver(coords, -1, receiver.prop(), index), parts.get(index));
                if (source != null) { result.add(source); }
            }
        }
        return result;
    }

    private static boolean scarReceiver(Renderable view) {
        var blend = view.material.get(BlendingAttribute.class, BlendingAttribute.Type);
        return drawsScars(view.material) && (blend == null || !blend.blended)
              && view.meshPart.primitiveType == GL20.GL_TRIANGLES && view.bones == null;
    }

    /**
     * Whether createShader draws a material with a program that draws its scar patches: the sculpt program or one from
     * model.frag (plain, instanced, liquid). A surface any other program draws receives no patch.
     */
    private static boolean drawsScars(Material material) {
        long mask = material.getMask();
        return (mask & (Foliage.TYPE | Pit.TYPE | GpuBackdrop.TYPE | GpuBiomeVegetation.Kind.TYPE | GpuGroundCover.Wind.TYPE)) == 0
              && ((mask & Sculpt.TYPE) != 0 || (mask & (Ground.TYPE | GpuWaterShader.TYPE | GpuMagmaShader.TYPE)) == 0);
    }

    private static GpuSurfaceScars.Source scarSource(GpuSurfaceScars.Receiver receiver, Renderable view) {
        return scarReceiver(view) ? new GpuSurfaceScars.Source(receiver, view) : null;
    }

    /** Ground cover of the installed tile, planted on its support at its chunk's detail; null when it grows none. */
    BoardPlants planted(Coords coords) {
        if (coverScene == null || coverScene.tile(coords) == null) { return null; }
        return chunks.get(coords.getX() / CHUNK_SIZE * chunkRows + coords.getY() / CHUNK_SIZE).tileMeshes.get(coords).plants;
    }

    /** Borrow a specific chunk's support, including during construction or while restoring a cached detail level. */
    private BoardTacticalGeometry.Surface tacticalSurface(BoardScene scene, Chunk chunk, Coords coords, float bottom) {
        TileMesh tile = chunk.tileMeshes.get(coords);
        if (tile.support != null) { return tile.support; }
        // Only a hex its preparation gave no support is built here, once.
        if (chunk.columns) {
            BoardScene.Tile source = scene.tile(coords);
            BoardSurface infrastructure = GpuTilesetTerrain.infrastructure(scene, source)
                  ? BoardSurface.columns(scene, source, chunk.lod) : null;
            Map<Coords, BoardSurface> sources = infrastructure == null ? Map.of() : Map.of(coords, infrastructure);
            tile.support = GpuTilesetTerrain.column(scene, source, bottom, infrastructure, sources);
        } else {
            // These readers (picking, draping, placement) take no ground cover support, which would sculpt up to six
            // neighbours too.
            BoardSurface surface = new BoardSurface(scene, scene.tile(coords), chunk.lod);
            tile.support = BoardTacticalGeometry.Surface.shape(surface, scene, bottom);
            tile.water = surface.tile.liquid().present() ? surface.waterGeometry() : null;
        }
        return tile.support;
    }

    /** Camera input borrows the installed mesh snapshot, including between receipt and installation of a new board. */
    void moveCamera(Vector3 eye, Vector3 movement, float radius) {
        if (coverScene != null) {
            installedSettings.run(() -> BoardCameraCollision.move(coverScene, this::tacticalSurface, eye, movement, radius));
        }
    }

    /**
     * Sculpted tops, cliffs and rock formations share a material per surface family; {@code ground} are the volcanic
     * tops, which keep their magma material. Shared vertices carry the canonical normal, occlusion and material masks
     * across mesh boundaries. {@code supports} are each blended face's final triangles, after water clipping and
     * material subdivision, as vertex triples (coatSupports).
     */
    record SculptPlan(List<BoardSurface.Face> walls, List<BoardSurface.Face> ground,
          List<BoardSurface.Face> formed, List<BoardSurface.Face> bed,
          Map<Vector3, Vector3> bedNormals,
          Map<GpuSurfaceBlend.Palette, List<GpuSurfaceBlend.Triangle>> blended,
          Map<GpuSurfaceBlend.Palette, List<GpuSurfaceBlend.Triangle>> blendedTops,
          Map<BoardSurface.Face, List<MeshPartBuilder.VertexInfo>> supports) { }

    static SculptPlan prepareSculpt(BoardScene scene, BoardScene.Tile tile, BoardSurface surface, float floor,
          TerrainLod lod, Map<Coords, BoardSurface> surfaces) {
        List<BoardSurface.Face> walls = surface.walls(scene, floor);
        boolean liquid = tile.liquid().present();
        List<BoardSurface.Face> ground = new ArrayList<>();
        List<BoardSurface.Face> formed = new ArrayList<>(walls);
        List<BoardSurface.Face> bed = new ArrayList<>();
        for (BoardSurface.Face face : surface.faces) {
            // A water hex's banks are sculpted ground whatever its artwork; its ice keeps the artwork.
            if (face.scatter()) { continue; }
            // Rough cover and dug ground are drawn by their models.
            if (face.finish() == BoardSurface.Finish.ROUGH || face.finish() == BoardSurface.Finish.DUG) { continue; }
            if (face.finish() == BoardSurface.Finish.ICE) { continue; }
            boolean volcanic = face.finish() == BoardSurface.Finish.TOP && tile.liquid().volcanic() && !liquid;
            (face.finish() == BoardSurface.Finish.BED ? bed : volcanic ? ground : formed).add(face);
        }
        // Keep canonical lighting across LoDs and material splits, including exposed bars at bank junctions.
        Map<Vector3, Vector3> bedNormals = wallNormals(bed);
        bed = surface.renderBed(bed);
        Map<GpuSurfaceBlend.Palette, List<GpuSurfaceBlend.Triangle>> groups = new LinkedHashMap<>();
        // Dry level tops in groups of their own, which the ground material cache can draw (blended).
        Map<GpuSurfaceBlend.Palette, List<GpuSurfaceBlend.Triangle>> tops = new LinkedHashMap<>();
        Map<BoardSurface.Face, List<MeshPartBuilder.VertexInfo>> supports = new HashMap<>();
        float spacing = GpuSurfaceBlend.spacing(lod);
        var sampler = new BoardSurfaceBlend.Sampler(scene, tile);
        // Open-water banks belong to their adjacent land. Process them with the shared water field below,
        // before a water hex's authored mixture can consume these faces and repaint the shore with its nominal theme.
        if (openWater(tile) == null && BoardSurfaceBlend.cliffBoundary(scene, tile)) {
            if (tile.liquid().volcanic() && tile.detailedGround()) {
                formed.addAll(ground);
                ground.clear();
            }
            List<BoardSurface.Face> blended = new ArrayList<>();
            formed.removeIf(face -> {
                var a = surface.relief.shade(face.a());
                var b = surface.relief.shade(face.b());
                var c = surface.relief.shade(face.c());
                boolean cover = a != null && b != null && c != null
                      && (a.kind() == BoardRelief.Kind.GROUND || a.kind().cliff()
                            || a.kind() == BoardRelief.Kind.SUBMERGED_CLIFF)
                      && a.kind() == b.kind() && a.kind() == c.kind();
                if (cover) { blended.add(face); }
                return cover;
            });
            var waters = GpuWaterCover.coveringWaters(scene, surface, surfaces);
            for (var face : blended) {
                boolean cliff = surface.relief.shade(face.a()).kind() != BoardRelief.Kind.GROUND;
                var target = GpuMaterialCache.top(surface, face, scene) ? tops : groups;
                blendFace(target, supports, face, pieces -> GpuWaterCover.coveredPolygons(surface, face, waters,
                      polygon -> GpuSurfaceBlend.appendPolygon(pieces, BoardSurfaceBlend.family(tile), polygon,
                            p -> cliff ? sampler.sampleCliff(p.x, p.y, p.z) : sampler.sample(p.x, p.y, p.z), spacing)));
            }
        }
        if (openWater(tile) != null) {
            // Every natural bank and bed triangle queries one shared field. Processing only the sectors facing
            // mixed land leaves radial wedges with the water hex's material, including at water-to-water edges.
            List<BoardSurface.Face> blended = new ArrayList<>();
            formed.removeIf(face -> {
                var land = face.landEdge() < 0 ? null
                      : scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(face.landEdge())));
                if (land != null && !land.liquid().present() && !BoardSurfaceBlend.natural(land)) { return false; }
                var a = surface.relief.shade(face.a());
                var b = surface.relief.shade(face.b());
                var c = surface.relief.shade(face.c());
                boolean cover = a != null && b != null && c != null
                      && (a.kind() == BoardRelief.Kind.GROUND || a.kind() == BoardRelief.Kind.SUBMERGED_CLIFF)
                      && b.kind() == a.kind() && c.kind() == a.kind();
                if (cover) { blended.add(face); }
                return cover;
            });
            var waters = GpuWaterCover.coveringWaters(scene, surface, surfaces);
            List<BoardSurface.Face> banks = new ArrayList<>();
            for (var face : blended) {
                boolean top = GpuMaterialCache.top(surface, face, scene);
                if (top) { banks.add(face); }
                blendFace(top ? tops : groups, supports, face, pieces -> GpuWaterCover.coveredPolygons(surface, face,
                      waters, polygon -> GpuSurfaceBlend.appendPolygon(pieces, surface.family(face), polygon,
                            p -> sampler.sample(p.x, p.y, p.z), spacing)));
            }
            // The hex's other faces that are tops (sculptedTriangles) shelter its bed faces as well.
            for (var face : formed) {
                if (GpuMaterialCache.top(surface, face, scene)) { banks.add(face); }
            }
            float shore = surface.bedUnderLevelWater() ? GpuWaterShader.palette(tile.liquid()) : Float.NaN;
            Function<Vector3, MeshPartBuilder.VertexInfo> vertices = p -> bedVertex(p, surface, bedNormals, shore);
            for (var face : bed) {
                var target = GpuMaterialCache.bed(surface, face, scene, banks) ? tops : groups;
                Consumer<List<MeshPartBuilder.VertexInfo>> blend = polygon -> {
                    if (tile.waterDepth() == 0) {
                        // An exposed bar still belongs to this shallow pool. Keep its water reference across the
                        // clipping boundary so sediment fades into dry cover instead of stopping at a triangle edge.
                        for (var vertex : polygon) {
                            if (vertex.color.a < .25f) { continue; }
                            var p = vertex.position;
                            vertex.setCol(waterColor(vertex.color.r, surface.waterHeight(p.x, p.y),
                                  shoreTint(GpuWaterShader.palette(tile.liquid()), (vertex.color.a - .3f) / .1f)));
                            vertex.uv.set(vertex.uv.y, shoreRim(vertex.uv.x, tile));
                        }
                    }
                    GpuSurfaceBlend.appendPolygon(target, surface.family(face), polygon,
                          p -> sampler.sample(p.x, p.y, p.z), spacing);
                };
                if (Float.isNaN(shore)) {
                    GpuWaterCover.coveredPolygons(face, waters, Integer.MAX_VALUE, vertices, blend);
                } else {
                    // The entire bed shares one water plane. Its surface's internal diagonals add no information
                    // here; clipping against them would turn a six-triangle floor into hundreds of slivers.
                    blend.accept(List.of(vertices.apply(face.a()), vertices.apply(face.b()), vertices.apply(face.c())));
                }
            }
            bed.clear();
        }
        return new SculptPlan(walls, ground, formed, bed, bedNormals, groups, tops, supports);
    }

    /** Appends one face's blended triangles to {@code groups}, keeping their vertices as its {@code supports} too. */
    private static void blendFace(Map<GpuSurfaceBlend.Palette, List<GpuSurfaceBlend.Triangle>> groups,
          Map<BoardSurface.Face, List<MeshPartBuilder.VertexInfo>> supports, BoardSurface.Face face,
          Consumer<Map<GpuSurfaceBlend.Palette, List<GpuSurfaceBlend.Triangle>>> append) {
        Map<GpuSurfaceBlend.Palette, List<GpuSurfaceBlend.Triangle>> pieces = new LinkedHashMap<>();
        append.accept(pieces);
        List<MeshPartBuilder.VertexInfo> triangles = new ArrayList<>();
        pieces.forEach((palette, part) -> {
            groups.computeIfAbsent(palette, key -> new ArrayList<>()).addAll(part);
            for (var t : part) { triangles.addAll(List.of(t.a().vertex(), t.b().vertex(), t.c().vertex())); }
        });
        supports.put(face, triangles);
    }

    /**
     * The ground's final triangles over a face, as vertex triples, where they are not the face itself: material
     * subdivision's and water clipping's (sculptedFaces) pieces; null where the ground draws the face whole. A curving
     * road coat follows them (GpuRoads.write), as a split edge's new point can curve where the whole edge would not.
     */
    static Function<BoardSurface.Face, List<MeshPartBuilder.VertexInfo>> coatSupports(SculptPlan plan,
          BoardSurface surface, BoardScene scene, Map<Coords, BoardSurface> surfaces) {
        Set<BoardSurface.Face> formed = new HashSet<>(plan.formed());
        Map<BoardSurface, GpuWaterCover> coverage = new IdentityHashMap<>();
        return face -> {
            var blended = plan.supports().get(face);
            if (blended != null || !formed.contains(face)) { return blended; }
            BoardSurface water = GpuWaterCover.clippingWater(surface, face, scene, surfaces);
            if (water == null) { return null; }
            List<MeshPartBuilder.VertexInfo> pieces = new ArrayList<>();
            GpuWaterCover waters = coverage.computeIfAbsent(water,
                  own -> GpuWaterCover.coveringWaters(scene, own, surfaces));
            GpuWaterCover.coveredPolygons(surface, face, waters, polygon -> GpuWaterCover.fan(polygon, pieces::addAll));
            return pieces;
        };
    }

    private void sculpt(GpuTerrainLayer solid, GpuTerrainLayer overlay, Chunk chunk, BoardScene scene,
          BoardScene.Tile tile, BoardSurface surface, Map<Coords, BoardSurface> surfaces, SculptPlan plan,
          Material magma) {
        List<BoardSurface.Face> walls = plan.walls(), ground = plan.ground(), formed = plan.formed();
        List<BoardSurface.Face> bed = plan.bed();
        // A water hex's ground carries its water's palette, so the shader wets it and tints it below the waterline.
        float shore = tile.liquid().present() ? GpuWaterShader.palette(tile.liquid()) : Float.NaN;
        // Built for tessellation, a full-detail chunk's triangles that curve take a material of their own
        // (sculptedTriangles).
        boolean split = chunk.tessellated && chunk.lod == TerrainLod.FULL && GpuTessellation.available();
        blended(solid, plan.blended(), scene, tile, chunk.lavaField, split, surface, false, chunk.charts);
        blended(solid, plan.blendedTops(), scene, tile, chunk.lavaField, split, surface, true, null);
        if (!plan.blendedTops().isEmpty()) { chunk.detailTops = true; }
        for (var group : byFamily(surface, formed).entrySet()) {
            Material formedMaterial = magma != null ? magma : sculptMaterial(group.getKey());
            // A frozen lake's banks below frozen land carry that land's ice, so one sheet spans the shore.
            boolean frozenLake = tile.frozen() && tile.liquid().present();
            List<BoardSurface.Face> iced = group.getValue().stream()
                  .filter(face -> frozenLake && frozenLand(scene, tile, face)).toList();
            List<BoardSurface.Face> plain = iced.isEmpty() ? group.getValue()
                  : group.getValue().stream().filter(face -> !frozenLand(scene, tile, face)).toList();
            boolean curves = split && magma == null;
            chunk.detailTops |= sculptedTriangles(solid, tile.liquid().present() ? formedMaterial
                  : GpuIceShader.ground(formedMaterial, scene, tile), curves, surface, plain, shore, scene, surfaces,
                  chunk.charts);
            if (!iced.isEmpty()) {
                chunk.detailTops |= sculptedTriangles(solid, GpuIceShader.land(formedMaterial), curves, surface, iced,
                      shore, scene, surfaces, chunk.charts);
            }
        }
        for (var group : byFamily(surface, bed).entrySet()) {
            solid.addTriangles(sculptMaterial(group.getKey()),
                  mesh -> bedFaces(mesh, surface, group.getValue(), shore, plan.bedNormals()));
        }
        if (!ground.isEmpty()) {
            // Volcanic tops keep their magma material on the sculpted outline; without its maps, the family's.
            if (magma != null) {
                solid.addTriangles(magma, mesh -> sculptedFaces(mesh, surface, ground, shore, scene, surfaces));
            } else {
                for (var group : byFamily(surface, ground).entrySet()) {
                    chunk.detailTops |= sculptedTriangles(solid, sculptMaterial(group.getKey()), split, surface,
                          group.getValue(), shore, scene, surfaces, chunk.charts);
                }
            }
        }
        for (BoardSurface.Face face : surface.faces) { chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
        for (BoardSurface.Face face : walls) { chunk.bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
    }

    /**
     * Sculpted faces in {@code material}; with {@code split}, those that curve in a copy of their own
     * (GpuTessellation#curved), so only they pass through the tessellation stages, and flat ground draws as without.
     * Whether sloping cache tops or charted walls ({@code charts}) are among them (Chunk#detailTops).
     */
    private static boolean sculptedTriangles(GpuTerrainLayer solid, Material material, boolean split,
          BoardSurface surface, List<BoardSurface.Face> faces, float shore, BoardScene scene,
          Map<Coords, BoardSurface> surfaces, GpuMaterialCache.Charts charts) {
        List<BoardSurface.Face> curved = new ArrayList<>(), plain = split ? new ArrayList<>() : faces;
        if (split) {
            for (var face : faces) {
                boolean curves = GpuTessellation.curves(face.a(), shadeNormal(surface, face.a()), face.b(),
                      shadeNormal(surface, face.b()), face.c(), shadeNormal(surface, face.c()));
                (curves ? curved : plain).add(face);
            }
        }
        List<BoardSurface.Face> rest = plain;
        boolean sloped = false;
        // Tops and charted walls in materials of their own, which the material cache can draw (GpuMaterialCache).
        if (GpuMaterialCache.cacheable(material)) {
            List<BoardSurface.Face> tops = new ArrayList<>(), walls = new ArrayList<>();
            List<float[]> places = new ArrayList<>();
            Map<BoardSurface, GpuWaterCover> coverage = new IdentityHashMap<>();
            rest = new ArrayList<>();
            for (var face : plain) {
                boolean top = GpuMaterialCache.top(surface, face, scene);
                // A chart takes a wall's face whole: one that water clips stays direct.
                float[] place = top || !GpuMaterialCache.wall(surface, face, scene)
                      || !GpuWaterCover.whole(surface, face, scene, surfaces, coverage) ? null
                      : charts.place(surface.tile.coords(), face.a(), face.b(), face.c());
                if (place != null) {
                    walls.add(face);
                    places.add(place);
                } else {
                    (top ? tops : rest).add(face);
                }
                sloped |= top && !GpuMaterialCache.level(face);
            }
            if (!tops.isEmpty()) {
                solid.addTriangles(GpuMaterialCache.top(material),
                      mesh -> sculptedFaces(mesh, surface, tops, shore, scene, surfaces));
            }
            if (!walls.isEmpty()) {
                solid.addTriangles(GpuMaterialCache.wall(material),
                      mesh -> chartedFaces(mesh, surface, walls, places, shore));
                sloped = true;
            }
        }
        List<BoardSurface.Face> direct = rest;
        if (!direct.isEmpty()) {
            solid.addTriangles(material, mesh -> sculptedFaces(mesh, surface, direct, shore, scene, surfaces));
        }
        if (!curved.isEmpty()) {
            solid.addTriangles(GpuTessellation.curvedCopy(material),
                  mesh -> sculptedFaces(mesh, surface, curved, shore, scene, surfaces));
        }
        return sloped;
    }

    /**
     * Charted walls' faces (GpuMaterialCache.Charts), {@code places} their corners' chart positions, over vertices
     * shared within a slot ({@link #chartedVertex}).
     */
    private static void chartedFaces(Supplier<MeshPartBuilder> triangles, BoardSurface surface,
          List<BoardSurface.Face> faces, List<float[]> places, float shore) {
        record Corner(Vector3 point, float x, float y) { }
        Map<Corner, Short> indices = new HashMap<>();
        MeshPartBuilder previous = null;
        short[] corners = new short[3];
        for (int i = 0; i < faces.size(); i++) {
            MeshPartBuilder mesh = triangles.get();
            if (mesh != previous) {
                // Cached indices belong to the old mesh.
                indices.clear();
                previous = mesh;
            }
            BoardSurface.Face face = faces.get(i);
            float[] place = places.get(i);
            Vector3[] points = { face.a(), face.b(), face.c() };
            for (int k = 0; k < 3; k++) {
                Vector3 p = points[k];
                float x = place[2 * k], y = place[2 * k + 1];
                corners[k] = indices.computeIfAbsent(new Corner(p, x, y),
                      key -> chartedVertex(mesh, sculptVertex(p, surface.relief.shade(p), shore, surface), x, y));
            }
            mesh.triangle(corners[0], corners[1], corners[2]);
        }
    }

    /** A charted wall's vertex in the standard layout, then its chart position (x, y; GpuMaterialCache.charted). */
    private static short chartedVertex(MeshPartBuilder mesh, MeshPartBuilder.VertexInfo v, float x, float y) {
        return mesh.vertex(v.position.x, v.position.y, v.position.z, v.color.toFloatBits(), v.normal.x, v.normal.y,
              v.normal.z, v.uv.x, v.uv.y, x, y);
    }

    /** The normal {@link #sculptVertex} gives a point. */
    private static Vector3 shadeNormal(BoardSurface surface, Vector3 point) {
        BoardRelief.Shade shade = surface.relief.shade(point);
        return shade == null ? Vector3.Z : shade.normal();
    }

    /**
     * Use the ordinary terrain materials and chunk batches; the shell has no per-bridge GPU resource. A muddy deck's
     * ({@code mud}) top carries {@link #DECK_MUD_KIND}; mud drains over its rim and down the adjacent rock shell. An
     * overhang's sides ({@code beds}) are shaded as the cliffs' beds.
     */
    private void naturalBridge(GpuTerrainLayer solid, BoardBridge.Shape shape, boolean mud, boolean artTop,
          boolean beds) {
        float metre = BoardRelief.metres(1);
        var mudDepth = mud ? naturalMudDepth(shape) : Map.<Vector3, Float>of();
        var normals = wallNormals(shape.facets().stream().map(face ->
              new BoardSurface.Face(face.a(), face.b(), face.c(), BoardSurface.Finish.WALL, -1)).toList());
        int family = shape.surface().ordinal();
        solid.addTriangles(sculptMaterial(shape.surface()), meshes -> {
            for (var face : shape.facets()) {
                if (artTop && face.part() == BoardBridge.Part.TOP) { continue; }
                var points = new MeshPartBuilder.VertexInfo[3];
                int i = 0;
                for (var p : List.of(face.a(), face.b(), face.c())) {
                    boolean top = face.part() == BoardBridge.Part.TOP;
                    boolean soffit = face.part() == BoardBridge.Part.SOFFIT;
                    boolean draining = mud && (face.part() == BoardBridge.Part.RIM || face.part() == BoardBridge.Part.SIDE);
                    float above = (p.z - shape.bounds().min.z) / metre;
                    float below = Math.max(0, shape.level() * BoardGeometry.level() - p.z) / metre;
                    // This shell otherwise has occlusion 1. Its spare byte carries local drop (0..8 m), leaving
                    // the cliff's existing foot/rim UVs intact; marker 140 restores occlusion in the shader.
                    var data = new Color(draining ? Math.min(1, mudDepth.get(p) / 8) : soffit ? .78f : 1,
                          top || soffit ? (shape.level() + 64) / 255f : 1,
                          top ? mud ? DECK_MUD_KIND : 0 : soffit ? 1
                                : draining ? DECK_MUD_RIM_KIND : .5f,
                          // An overhang's sides take the hardness of the cliffs' bed at each height.
                          top ? .6f : beds && !soffit ? BoardRelief.bed(p, family) : .55f);
                    points[i++] = vertex(p, normals.get(p), top ? Math.max(.02f, 2.5f - below * 6) : above,
                          top ? 99 : below, data);
                }
                meshes.get().triangle(points[0], points[1], points[2]);
            }
        });
    }

    /** Drop below the nearest walking-surface edge, shared by the rim and sides even on a descending span. */
    static Map<Vector3, Float> naturalMudDepth(BoardBridge.Shape shape) {
        Set<Vector3> top = new HashSet<>(), shell = new HashSet<>();
        shape.facets().stream().filter(face -> face.part() == BoardBridge.Part.TOP)
              .forEach(face -> { top.add(face.a()); top.add(face.b()); top.add(face.c()); });
        var edges = new ArrayList<Vector3[]>();
        for (var face : shape.facets()) {
            if (face.part() != BoardBridge.Part.RIM && face.part() != BoardBridge.Part.SIDE) { continue; }
            Vector3[] points = { face.a(), face.b(), face.c() };
            for (int i = 0; i < 3; i++) {
                var a = points[i]; var b = points[(i + 1) % 3];
                shell.add(a);
                if (top.contains(a) && top.contains(b)) { edges.add(new Vector3[] { a, b }); }
            }
        }
        var result = new HashMap<Vector3, Float>();
        for (var point : shell) {
            float nearest = Float.POSITIVE_INFINITY, height = point.z;
            for (var edge : edges) {
                var a = edge[0]; var b = edge[1];
                float dx = b.x - a.x, dy = b.y - a.y, length = dx * dx + dy * dy;
                float t = length == 0 ? 0 : Math.clamp(((point.x - a.x) * dx + (point.y - a.y) * dy) / length, 0, 1);
                float x = point.x - a.x - t * dx, y = point.y - a.y - t * dy;
                float distance = x * x + y * y;
                if (distance < nearest) { nearest = distance; height = a.z + (b.z - a.z) * t; }
            }
            result.put(point, Math.max(0, height - point.z) / BoardRelief.metres(1));
        }
        return result;
    }

    /**
     * Generate GPU batches from the same prepared bridge facets used for placement and picking. A deck road over every
     * exit paints the whole top, which then takes its material; any other top is bare concrete.
     */
    private void builtBridge(GpuTerrainLayer solid, BoardBridge.Shape shape, BoardBridge.Deck bridge,
          BoardScene scene, BoardScene.Tile tile) {
        Material concrete = material(assets.material("terrain/surfaces/concrete-ground"), false);
        if (BoardBridge.guideway(tile)) {
            // The route model draws the beam; these same bridge facets own its picking and its piers.
            var piers = BoardBridge.shape(shape.surface(), shape.level(),
                  shape.facets().stream().filter(f -> f.part() == BoardBridge.Part.PIER).toList());
            if (!piers.facets().isEmpty()) { bridgeFooting(solid, piers, concrete, concrete, null); }
            return;
        }
        Material deck = concrete;
        if (bridge.paved()) {
            String road = GpuRoads.material(tile, true, GpuRoads.texture(bridge.kind()));
            deck = roadMaterial(road, false);
            deck.set(GpuIceShader.road(deck, scene, tile));
            deck.set(new BridgeDeck(assets.roadCoverage("roads/" + road)));
        }
        Material steel = bridge.truss() ? new Material(ColorAttribute.createDiffuse(Color.WHITE), new GpuLinearColors()) : null;
        bridgeFooting(solid, shape, deck, concrete, steel);
    }

    /**
     * A built deck's terrain facets: its top in the road material, a truss's steel ({@code steel}, null for
     * the standard kit and piers) in its facets' recoloured colour, everything else in the structure's concrete.
     */
    private void bridgeFooting(GpuTerrainLayer solid, BoardBridge.Shape shape, Material deck, Material structure,
          Material steel) {
        float coverage = assets.coverage("terrain/surfaces/concrete-ground");
        for (var part : List.of(BoardBridge.Part.TOP, BoardBridge.Part.STRUCTURE, BoardBridge.Part.STEEL)) {
            if (part == BoardBridge.Part.STEEL && (steel == null || shape.facets().stream().noneMatch(f -> f.part() == part))) {
                continue;
            }
            Material material = part == BoardBridge.Part.TOP ? deck : part == BoardBridge.Part.STEEL ? steel : structure;
            if (part == BoardBridge.Part.STEEL) {
                // The generated facets already carry the span's linear steel colour.
                material = material.copy();
                material.set(ColorAttribute.createDiffuse(Color.WHITE), new GpuLinearColors());
            }
            solid.addTriangles(material, meshes -> {
                for (var face : shape.facets()) {
                    var drawn = face.part() == BoardBridge.Part.TOP || face.part() == BoardBridge.Part.STEEL && steel != null
                          ? face.part() : BoardBridge.Part.STRUCTURE;
                    if (drawn != part) { continue; }
                    bridgeFace(meshes.get(), face.a(), face.b(), face.c(), face.normal(),
                          face.colour() == null ? Color.WHITE : face.colour(), coverage);
                }
            });
        }
    }

    /**
     * Decks, terminals and their supports share one world-space concrete texture scale, widened with the concrete
     * texture ({@code coverage}, GpuAssets.coverage).
     */
    private static void bridgeFace(MeshPartBuilder mesh, Vector3 a, Vector3 b, Vector3 c, Vector3 normal, Color colour,
          float coverage) {
        var points = new MeshPartBuilder.VertexInfo[3];
        int i = 0;
        float repeat = BoardGeometry.width() / 5 * coverage;
        for (var p : List.of(a, b, c)) {
            float u = Math.abs(normal.z) > .5f || Math.abs(normal.x) <= .5f ? p.x : p.y;
            float v = Math.abs(normal.z) > .5f ? -p.y : -p.z;
            points[i++] = vertex(p, normal, u / repeat, v / repeat, colour);
        }
        mesh.triangle(points[0], points[1], points[2]);
    }

    /** {@code lava} is the chunk's lava field: cooled banks, the lava's own and those of land beside it, glow at it. */
    private void blended(GpuTerrainLayer solid, Map<GpuSurfaceBlend.Palette, List<GpuSurfaceBlend.Triangle>> groups,
          BoardScene scene, BoardScene.Tile tile, GpuWaterShader.Field lava) {
        blended(solid, groups, scene, tile, lava, false, null, false, null);
    }

    /**
     * With {@code split}, the triangles that curve (magma's aside) take a material of their own: sculptedTriangles.
     * {@code tops}: dry level tops (GpuMaterialCache.top), which the ground material cache draws where it can; else
     * with {@code charts} its natural walls, charted where they fit (GpuMaterialCache.Charts).
     */
    private void blended(GpuTerrainLayer solid, Map<GpuSurfaceBlend.Palette, List<GpuSurfaceBlend.Triangle>> groups,
          BoardScene scene, BoardScene.Tile tile, GpuWaterShader.Field lava, boolean split, BoardSurface surface,
          boolean tops, GpuMaterialCache.Charts charts) {
        for (var group : groups.entrySet()) {
            var palette = group.getKey();
            boolean boundary = palette.first() != palette.base() || palette.second() != palette.base() || palette.third() != palette.base();
            Material material = boundary ? blendMaterial(palette) : sculptMaterial(palette.base());
            GpuMagmaShader magma = material.get(GpuMagmaShader.class, GpuMagmaShader.TYPE);
            if (lava != null && magma != null) {
                material = new Material(material);
                material.set(magma.withField(lava));
            }
            if (tile != null && !tile.liquid().present()) { material = GpuIceShader.ground(material, scene, tile); }
            List<GpuSurfaceBlend.Triangle> curved = new ArrayList<>();
            List<GpuSurfaceBlend.Triangle> plain = split && magma == null ? new ArrayList<>() : group.getValue();
            if (split && magma == null) {
                for (var triangle : group.getValue()) {
                    var a = triangle.a().vertex();
                    var b = triangle.b().vertex();
                    var c = triangle.c().vertex();
                    boolean curves = GpuTessellation.curves(a.position, a.normal, b.position, b.normal, c.position,
                          c.normal);
                    (curves ? curved : plain).add(triangle);
                }
            }
            List<GpuSurfaceBlend.Triangle> walls = new ArrayList<>();
            List<float[]> places = new ArrayList<>();
            if (charts != null && GpuMaterialCache.cacheable(material)) {
                List<GpuSurfaceBlend.Triangle> rest = new ArrayList<>();
                for (var triangle : plain) {
                    var a = triangle.a().vertex();
                    var b = triangle.b().vertex();
                    var c = triangle.c().vertex();
                    float[] place = GpuMaterialCache.wall(scene, tile, a, b, c)
                          ? charts.place(tile.coords(), a.position, b.position, c.position) : null;
                    if (place != null) {
                        walls.add(triangle);
                        places.add(place);
                    } else {
                        rest.add(triangle);
                    }
                }
                plain = rest;
            }
            blendedTriangles(solid, tops && GpuMaterialCache.cacheable(material) ? GpuMaterialCache.top(material)
                  : material, palette, boundary, plain, null, null);
            if (!walls.isEmpty()) {
                blendedTriangles(solid, GpuMaterialCache.wall(material), palette, boundary, walls, null, places);
            }
            if (!curved.isEmpty()) {
                blendedTriangles(solid, GpuTessellation.curvedCopy(material), palette, boundary, curved, surface, null);
            }
        }
    }

    /** {@code places}: charted walls' chart positions, one array a triangle (GpuMaterialCache.Charts), else null. */
    private static void blendedTriangles(GpuTerrainLayer solid, Material material, GpuSurfaceBlend.Palette palette,
          boolean boundary, List<GpuSurfaceBlend.Triangle> triangles, BoardSurface surface, List<float[]> places) {
        if (triangles.isEmpty()) { return; }
        solid.addTriangles(material, meshes -> {
            for (int i = 0; i < triangles.size(); i++) {
                var triangle = triangles.get(i);
                float[] place = places == null ? null : places.get(i);
                MeshPartBuilder mesh = meshes.get();
                if (boundary) { triangle.write(mesh, palette, surface, place); }
                else if (place != null) {
                    mesh.triangle(chartedVertex(mesh, triangle.a().vertex(), place[0], place[1]),
                          chartedVertex(mesh, triangle.b().vertex(), place[2], place[3]),
                          chartedVertex(mesh, triangle.c().vertex(), place[4], place[5]));
                } else {
                    mesh.triangle(GpuTessellation.vertex(mesh, triangle.a().vertex(), surface),
                          GpuTessellation.vertex(mesh, triangle.b().vertex(), surface),
                          GpuTessellation.vertex(mesh, triangle.c().vertex(), surface));
                }
            }
        });
    }

    /** {@code supports}: the ground's final triangles where its coats curve with it (coatSupports), else null. */
    private void roads(GpuTerrainLayer overlay, List<RoadPatch> prepared, BoardSurface surface,
          GpuTextures<BoardScene.Pixels> masks, BoardScene scene, BoardScene.Tile tile,
          Function<BoardSurface.Face, List<MeshPartBuilder.VertexInfo>> supports) {
        // A hex without coats needs no road arrays: a board without any never builds them.
        if (prepared.isEmpty()) {
            overlay.route = false;
            return;
        }
        var roadMaps = assets.roadArray();
        for (var preparedPatch : prepared) {
            overlay.route = routeCoat(preparedPatch);
            var patch = preparedPatch.patch();
            var mask = new GpuRoads.Mask(masks.region(preparedPatch.mask().pixels()), preparedPatch.mask());
            String road = GpuRoads.material(preparedPatch.source(), preparedPatch.flat(), patch.texture());
            int maps = road != null ? GpuRoads.MATERIALS.indexOf(road)
                  : GpuRoads.MATERIALS.size() + SCULPT_LAYERS.indexOf(patch.texture());
            Material material;
            float coat = Color.WHITE.toFloatBits();
            if (roadMaps != null && maps >= (road != null ? 0 : GpuRoads.MATERIALS.size())) {
                // Every coat of the chunk shares this material; its own maps, finish and response are in its vertices.
                // The mask page stands in as the diffuse texture only to give the coat its texture coordinates.
                material = material(mask.textureDescription.texture, true);
                material.set(new Ground(.65f));
                material.set(new GpuRoads.Coats(roadMaps, terrainLayers()));
                coat = GpuRoads.coat(maps, patch, "dirt".equals(road) ? .15f : .65f);
            } else if (road != null) {
                material = roadMaterial(road, true);
            } else {
                var sculpt = assets.sculpt(patch.texture());
                material = material(sculpt.color(), true);
                material.set(TextureAttribute.createNormal(sculpt.normal()));
                material.set(new Ground(.65f));
            }
            material.set(GpuRoads.attribute(patch));
            material.set(mask);
            material = GpuIceShader.road(material, scene, tile);
            // A coat lies lifted over its ground. Past a crest steeper than the view its underside would show as a dark
            // line above the ground's edge: only the coat's top is ever seen.
            material.set(IntAttribute.createCullFace(GL20.GL_BACK));
            float vertexCoat = coat;
            // The coat's own material sets its repeat, as the catalogue gives it: a road's, or the sculpted material
            // it paints, which so meets its own ground at the same scale.
            float coverage = road != null ? assets.roadCoverage("roads/" + road) : assets.sculptCoverage(patch.texture());
            boolean curves = supports != null && !preparedPatch.flat() && !tile.liquid().volcanic();
            if (curves) { material = GpuTessellation.curvedCopy(material); }
            overlay.addTriangles(material, meshes -> GpuRoads.write(meshes, preparedPatch.triangles(), patch, mask, surface,
                  preparedPatch.flat(), vertexCoat, coverage, curves, curves ? supports : null));
        }
        overlay.route = false;
    }

    private Material roadMaterial(String name, boolean blended) {
        var maps = assets.road(name);
        Material material = material(maps.color(), blended);
        if (maps.normal() != null) { material.set(TextureAttribute.createNormal(maps.normal())); }
        if (maps.surface() != null) { material.set(new GpuRoads.Maps(maps.surface(), GpuRoads.MATERIALS.indexOf(name))); }
        material.set(new Ground(name.equals("dirt") ? .15f : .65f));
        if (name.equals("dirt")) { material.set(new GpuRoads.Soil()); }
        return material;
    }

    private static Map<BoardScene.Surface, List<BoardSurface.Face>> byFamily(BoardSurface surface,
          List<BoardSurface.Face> faces) {
        Map<BoardScene.Surface, List<BoardSurface.Face>> groups = new EnumMap<>(BoardScene.Surface.class);
        for (BoardSurface.Face face : faces) {
            groups.computeIfAbsent(surface.family(face), key -> new ArrayList<>()).add(face);
        }
        return groups;
    }

    /** A water hex's bed, smoothly shaded over shared vertices; shore is its water's palette. */
    private static void bedFaces(Supplier<MeshPartBuilder> triangles, BoardSurface surface,
          List<BoardSurface.Face> bed, float shore, Map<Vector3, Vector3> normals) {
        Map<Vector3, Short> indices = new HashMap<>();
        MeshPartBuilder previous = null;
        Function<Vector3, Short> shared = null;
        for (BoardSurface.Face face : bed) {
            MeshPartBuilder mesh = triangles.get();
            if (mesh != previous) {
                indices.clear();
                shared = p -> indices.computeIfAbsent(p, key -> mesh.vertex(bedVertex(key, surface, normals, shore)));
                previous = mesh;
            }
            mesh.triangle(shared.apply(face.a()), shared.apply(face.b()), shared.apply(face.c()));
        }
    }

    private static MeshPartBuilder.VertexInfo bedVertex(Vector3 p, BoardSurface surface,
          Map<Vector3, Vector3> normals, float shore) {
        BoardRelief.Shade bank = surface.relief.shade(p);
        if (bank != null) { return sculptVertex(p, bank, shore, surface); }
        Color data = Float.isNaN(shore) ? new Color(1, (surface.tile.elevation() + 64) / 255f, 0, .3f)
              : waterColor(1, surface.waterHeight(p.x, p.y), shoreTint(shore, 0));
        return vertex(p, normals.get(p), 99, Float.isNaN(shore) ? 99 : shoreRim(99, surface.tile), data);
    }

    private Material sculptMaterial(BoardScene.Surface family) {
        // The diffuse slot only enables the default vertex shader's UV output; the sculpt maps are bound below.
        Material material = new Material("sculpt-" + family, TextureAttribute.createDiffuse(rainNoise),
              IntAttribute.createCullFace(GL20.GL_BACK));
        material.set(new Sculpt(family.ordinal()), new Ground(family == BoardScene.Surface.SNOW ? -1
              : groundResponse(family)));
        material.set(new SculptTiles(sculptTiles(family)),
              new SculptLayers(terrainLayers(), sculptLayers(family)));
        return material;
    }

    /** Metres per repeat of the family's ground, debris, wall and mantle maps. */
    private float[] sculptTiles(BoardScene.Surface family) {
        float[] tiles = new float[4];
        for (int role = 0; role < 4; role++) { tiles[role] = assets.sculptTile(SCULPT_MATERIALS[family.ordinal()][role]); }
        return tiles;
    }

    /** The colour/height layers of the family's ground, debris, wall and mantle maps in the sculpt array. */
    private static float[] sculptLayers(BoardScene.Surface family) {
        float[] layers = new float[4];
        for (int role = 0; role < 4; role++) { layers[role] = 2 * SCULPT_LAYERS.indexOf(SCULPT_MATERIALS[family.ordinal()][role]); }
        return layers;
    }

    private Material sculptMaterial(int family) {
        if (family < BoardSurfaceBlend.CRUST) { return sculptMaterial(BoardScene.Surface.values()[family]); }
        Material material = sculptMaterial(BoardScene.Surface.ROCK);
        Material volcanic = GpuMagmaShader.material(assets,
              family == BoardSurfaceBlend.BANK ? GpuMagmaShader.BANK : GpuMagmaShader.CRUST, null);
        if (volcanic != null) {
            for (Attribute attribute : volcanic) { material.set(attribute.copy()); }
            material.set(new Sculpt(family), new Ground(-1));
        }
        return material;
    }

    private Material blendMaterial(GpuSurfaceBlend.Palette palette) {
        Material material = sculptMaterial(palette.base());
        var families = List.of(palette.base(), palette.first(), palette.second(), palette.third());
        float[] ids = new float[4], responses = new float[4];
        float[][] tiles = new float[4][], layers = new float[4][];
        for (int i = 0; i < families.size(); i++) {
            var family = families.get(i);
            ids[i] = family;
            var ordinary = family >= BoardSurfaceBlend.CRUST ? BoardScene.Surface.ROCK : BoardScene.Surface.values()[family];
            responses[i] = family >= BoardSurfaceBlend.CRUST ? -1 : groundResponse(ordinary);
            tiles[i] = sculptTiles(ordinary);
            layers[i] = sculptLayers(ordinary);
        }
        material.set(new GpuSurfaceBlend(terrainLayers(), ids, responses, tiles, layers));
        if (palette.volcanic() && !material.has(GpuMagmaShader.TYPE)) {
            Material volcanic = GpuMagmaShader.material(assets, GpuMagmaShader.CRUST, null);
            if (volcanic != null) { for (Attribute attribute : volcanic) { material.set(attribute.copy()); } }
        }
        return material;
    }

    /** Sculpted faces over shared vertices; shore, unless NaN, is the palette of the water hex they belong to. */
    static void sculptedFaces(Supplier<MeshPartBuilder> triangles, BoardSurface surface, List<BoardSurface.Face> faces,
          float shore, BoardScene scene, Map<Coords, BoardSurface> surfaces) {
        Map<Vector3, Short> indices = new java.util.IdentityHashMap<>();
        MeshPartBuilder previous = null;
        Function<Vector3, Short> vertex = null;
        Map<BoardSurface, GpuWaterCover> coverage = new java.util.IdentityHashMap<>();
        for (BoardSurface.Face face : faces) {
            BoardSurface water = GpuWaterCover.clippingWater(surface, face, scene, surfaces);
            if (water != null) {
                GpuWaterCover waters = coverage.computeIfAbsent(water,
                      own -> GpuWaterCover.coveringWaters(scene, own, surfaces));
                GpuWaterCover.coveredFace(triangles, surface, face, waters);
                continue;
            }
            MeshPartBuilder mesh = triangles.get();
            if (mesh != previous) {
                // Cached indices belong to the old mesh, including when wet polygons caused the split.
                indices.clear();
                vertex = p -> indices.computeIfAbsent(p,
                      key -> GpuTessellation.vertex(mesh, sculptVertex(key, surface.relief.shade(key), shore, surface), surface));
                previous = mesh;
            }
            mesh.triangle(vertex.apply(face.a()), vertex.apply(face.b()), vertex.apply(face.c()));
        }
    }

    static MeshPartBuilder.VertexInfo sculptVertex(Vector3 p, BoardRelief.Shade shade, float shore, BoardSurface surface) {
        if (shade == null) {
            return vertex(p, Vector3.Z, 0, 0, new Color(1, 64 / 255f, 1, 1));
        }
        if (shade.kind() == BoardRelief.Kind.ROCK && openWater(surface.tile) != null && !Float.isNaN(shore)) {
            // Keep the rock projection and pack water optics in its spare kind range (blue bytes 224..254).
            // Alpha carries the water palette; the unused second UV retains pigment variation separately from AO.
            Color data = waterColor(shade.occlusion(),
                  surface.waterHeight(p.x, p.y), shoreTint(shore, 0));
            data.b = (224 + 240 * data.b) / 255f;
            return vertex(p, shade.normal(), shade.rim(), shade.tint(), data);
        }
        float kind = switch (shade.kind()) {
            case GROUND, SUBMERGED_CLIFF -> 0;
            // .25 is the earlier shrub format; .3/.35 carry authored wood/leaf RGB in UV/alpha.
            case PLANT -> .3f;
            case LEAF -> .35f;
            case CLIFF -> Float.isFinite(shade.projection()) ? .45f : .5f;
            case PIT_WALL -> .6f;
            case PIT -> .75f;
            case ROCK -> 1;
        };
        // A cliff carries its rockiness here; everything else its integer game level.
        float level = shade.kind().cliff() ? Math.clamp(shade.level(), 0, 1)
              : Math.clamp((Math.round(shade.level()) + 64) / 255f, 0, 1);
        boolean submergedCliff = shade.kind() == BoardRelief.Kind.SUBMERGED_CLIFF;
        boolean wet = !Float.isNaN(shore) && (shade.kind() == BoardRelief.Kind.GROUND || submergedCliff);
        // A raised stream cannot drown the bank outside its channel. Below the hex's own waterline, however, the
        // bank is submerged too, including where a beach meets the foot of a cliff.
        float water = wet ? surface.waterHeight(p.x, p.y) : 0;
        if (wet && !submergedCliff) { water = Math.min(water, Math.max(p.z, BoardGeometry.waterZ(surface.tile))); }
        Color data = wet ? waterColor(shade.occlusion(), water,
              shoreTint(shore, (shade.tint() - .3f) / .1f)) : new Color(shade.occlusion(), level, kind, shade.tint());
        if (shade.kind() == BoardRelief.Kind.CLIFF && Float.isFinite(shade.projection())) {
            data.a = .5f + shade.projection() / (2 * (float) Math.PI);
        }
        // Ordinary ground stores rim/foot distances; the shore shader reads foot/rim, as drowned walls do.
        // Drowned cliffs already carry that wall-oriented order and must not be swapped again.
        boolean wetGround = wet && shade.kind() == BoardRelief.Kind.GROUND;
        float rim = wetGround ? shade.rim() : shade.foot();
        return vertex(p, shade.normal(), wetGround ? shade.foot() : shade.rim(),
              wet ? shoreRim(rim, surface.tile) : rim, data);
    }

    /** Depth-zero ownership in the sign of an otherwise nonnegative shore distance; no extra vertex channel. */
    static float shoreRim(float distance, BoardScene.Tile water) {
        return water.waterDepth() == 0 ? -1 - distance : distance;
    }

    /** Ground's otherwise unused blue range holds the fractional water level, preserving sloped-bed optics. */
    static Color waterColor(float occlusion, float waterHeight, float tint) {
        float level = (waterHeight + BoardGeometry.hexScale()) / BoardGeometry.level();
        float whole = (float) Math.floor(level);
        return new Color(occlusion, Math.clamp((whole + 64) / 255f, 0, 1), (level - whole) / 8, tint);
    }

    /**
     * The tint of a water hex's ground: below a quarter, which tells the shader to wet it and tint it under the hex's
     * water. It packs the water's palette with the nearest step's height in levels, up to seven, as terrain-sculpt.frag
     * unpacks them; packed colours keep only even alpha bytes.
     */
    static float shoreTint(float palette, float levels) {
        return (16 * palette + 1 + 2 * Math.clamp(levels, 0, 7)) / 255f;
    }

    private static Map<Vector3, Vector3> wallNormals(List<BoardSurface.Face> faces) {
        return BoardSurface.vertexNormals(faces);
    }

    /** The tile itself when it holds open water that can cover a face; frozen, molten and dry tiles do not. */
    static BoardScene.Tile openWater(BoardScene.Tile tile) {
        return tile != null && tile.liquid().present() && !tile.liquid().molten() && !tile.frozen() ? tile : null;
    }

    /**
     * The water surface keeps its bank outline; the chunk's field supplies depth, bank distance and current per
     * pixel, so only the blended rapids ride on the vertices. Authored GIF water maps its artwork as before.
     */
    private static void waterSurface(MeshPartBuilder mesh, GpuWaterShader.Field field, Coords coords,
          List<BoardSurface.Face> faces, TextureRegion art, Map<Vector3, Vector3> normals) {
        Map<Vector3, Short> indices = new HashMap<>();
        for (BoardSurface.Face face : faces) {
            short a = indices.computeIfAbsent(face.a(), p -> mesh.vertex(waterVertex(field, coords, p, normals.get(p), art)));
            short b = indices.computeIfAbsent(face.b(), p -> mesh.vertex(waterVertex(field, coords, p, normals.get(p), art)));
            short c = indices.computeIfAbsent(face.c(), p -> mesh.vertex(waterVertex(field, coords, p, normals.get(p), art)));
            mesh.triangle(a, b, c);
        }
    }

    /**
     * The board-edge section uses the cut-water material. Green stores its depth below the surface over
     * {@link GpuWaterShader#DEPTH_RANGE} levels.
     */
    private static void waterCut(MeshPartBuilder mesh, float top, BoardSurface.Face face) {
        mesh.triangle(cutVertex(face.a(), top), cutVertex(face.b(), top), cutVertex(face.c(), top));
    }

    private static MeshPartBuilder.VertexInfo cutVertex(Vector3 p, float top) {
        float below = Math.clamp((top - p.z) / (GpuWaterShader.DEPTH_RANGE * BoardGeometry.level()), 0, 1);
        return vertex(p, Vector3.Z, p.x / BoardGeometry.width(), -p.y / BoardGeometry.width(), new Color(0, below, 1, 1));
    }

    private static MeshPartBuilder.VertexInfo waterVertex(GpuWaterShader.Field field, Coords coords, Vector3 p, Vector3 normal,
          TextureRegion art) {
        Color agitation = new Color(field.agitation(p), 0, 0, 1);
        return art == null ? vertex(p, normal, p.x / BoardGeometry.width(), -p.y / BoardGeometry.width(), agitation)
              : topVertex(p, coords, art, agitation).setNor(normal);
    }

    /** How much water film a surface's exposed material takes; negative excludes snow. */
    private static float groundResponse(BoardScene.Surface surface) {
        return switch (surface) {
            case SNOW -> -1;
            case SAND -> 0.05f;
            case DESERT, MARS -> 0.12f;
            case DIRT -> 0.15f;
            case GRASS -> 0.25f;
            case ROCK, LUNAR, VOLCANO -> 0.7f;
            case FUNGUS, TROPICAL -> 0.35f;
            case CONCRETE -> 1;
        };
    }

    /** Opaque black, without terrain pigment, specular response or transparency: the pit cannot reveal the sky. */
    static Material pitMaterial() {
        return new Material("pit", new Pit(), ColorAttribute.createDiffuse(Color.BLACK),
              IntAttribute.createCullFace(GL20.GL_NONE));
    }

    private static Material material(Texture texture, boolean blend) {
        Material material = new Material("surface", TextureAttribute.createDiffuse(texture),
              IntAttribute.createCullFace(GL20.GL_NONE));
        if (blend) {
            material.set(new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA));
            material.set(new DepthTestAttribute(GL20.GL_LEQUAL, false));
        }
        return material;
    }

    /** Shows the liquid's current frame, moved on by its current or, falling, down its fall; magma on its own clock. */
    private void animateLiquid(LiquidSurface liquid) {
        GpuAssets.Animation<Texture> frames = assets.liquidAnimation(liquid.source());
        TextureAttribute texture = liquid.material().get(TextureAttribute.class, TextureAttribute.Diffuse);
        TextureAttribute emission = liquid.material().get(TextureAttribute.class, TextureAttribute.Emissive);
        float liquidTime = emission == null ? clock : magmaClock;
        int index = frames.index(liquidTime);
        Texture frame = frames.frames().get(index);
        float offsetU = (liquidTime * liquid.current().u()) % 1;
        float offsetV = liquid.falling() ? (liquidTime * (emission == null ? 1 : 0.25f)) % 1 : (liquidTime * liquid.current().v()) % 1;
        texture.textureDescription.texture = frame;
        texture.offsetU = offsetU;
        texture.offsetV = offsetV;
        GpuLiquidShader.Frame next = liquid.material().get(GpuLiquidShader.Frame.class, GpuLiquidShader.Frame.TYPE);
        if (next != null) {
            next.textureDescription.texture = frames.frames().get((index + 1) % frames.frames().size());
            next.blend = frames.blend(liquidTime, index);
        }
        if (emission != null) {
            emission.textureDescription.texture = frame;
            emission.offsetU = offsetU;
            emission.offsetV = offsetV;
        }
    }

    /** Magma glows in its own frames; hazardous liquid is tinted green. The Tactical View's liquids share this. */
    static void liquidColour(Material material, BoardLiquid liquid, Texture frame) {
        material.set(new GpuLinearColors());
        if (liquid.molten()) {
            material.set(ColorAttribute.createDiffuse(GpuColor.linear(.35f, .35f, .35f)));
            material.set(TextureAttribute.createEmissive(frame),
                  ColorAttribute.createEmissive(GpuColor.linear(.65f, .65f, .65f)));
        } else if (liquid.kind() == BoardLiquid.Kind.HAZARDOUS) {
            material.set(ColorAttribute.createDiffuse(GpuColor.linear(.4f, 1, .12f)));
        }
    }

    private Material liquidMaterial(BoardScene scene, BoardSurface surface, BoardLiquid.Textures source,
          boolean falling, GpuWaterShader.Field field) {
        BoardLiquid liquid = surface.tile.liquid();
        if (liquid.molten()) {
            Material magma = GpuMagmaShader.material(assets, falling ? GpuMagmaShader.FALL : GpuMagmaShader.LAVA, field);
            if (magma != null) { return magma; }
        }
        boolean procedural = proceduralWater && !liquid.molten();
        Texture texture = procedural ? rainNoise : assets.liquid(source, 0);
        Material material = material(texture, !liquid.molten());
        // Procedural water varies per pixel, not per hex, so every depth and rapids class shares one draw.
        material.id = (falling ? "falls:" : "liquid:") + liquid.kind() + ":"
              + (procedural ? liquid.theme() : source.base() + ":" + source.foam() + ":" + source.tone());
        if (liquidShaderAnimation && !procedural) {
            material.set(new GpuLiquidShader.Frame(texture));
        }
        if (!liquid.molten()) {
            // Premultiplied: the shader adds reflected and scattered light and removes only what the column absorbs.
            material.set(new BlendingAttribute(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA,
                  procedural ? 1 : falling ? 0.8f : GpuWaterShader.SURFACE_OPACITY));
            GpuWaterShader water = new GpuWaterShader(scene, surface, procedural, falling, field);
            material.set(water);
            if (!water.impacts.isEmpty()) { material.id += ":splash:" + surface.tile.coords(); }
        }
        liquidColour(material, liquid, texture);
        return material;
    }

    private static MeshPartBuilder.VertexInfo vertex(Vector3 p, Vector3 normal, float u, float v, Color color) {
        return new MeshPartBuilder.VertexInfo().setPos(p).setNor(normal).setUV(u, v).setCol(color);
    }

    static MeshPartBuilder.VertexInfo topVertex(Vector3 p, Coords coords, TextureRegion region, Color color) {
        // Sample just inside the artwork's alpha border, without moving the actual geometry.
        float u = 0.5f + (p.x - BoardGeometry.centerX(coords)) / BoardGeometry.width() * BoardRim.GROUND_UV_SCALE;
        float v = 0.5f - (p.y - BoardGeometry.centerY(coords)) / BoardGeometry.height() * BoardRim.GROUND_UV_SCALE;
        return vertex(p, Vector3.Z, region.getU() + u * (region.getU2() - region.getU()),
              region.getV() + v * (region.getV2() - region.getV()), color);
    }

    private static void surface(MeshPartBuilder mesh, Coords coords, BoardSurface.Face face,
          TextureRegion art, float lift) {
        Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
        mesh.triangle(surfaceVertex(face.a(), coords, normal, art, lift),
              surfaceVertex(face.b(), coords, normal, art, lift), surfaceVertex(face.c(), coords, normal, art, lift));
    }

    /** One flat face of a precast wall; its concrete repeats every three metres along and up the wall. */
    private static void panel(MeshPartBuilder mesh, BoardSurface.Face face) {
        panel(mesh, face, BoardRelief.metres(3));
    }

    /**
     * A Solaris elevator's platform and lift in the shared finishes; its paint, steel and iron darken with grime where
     * wear gathers.
     */
    private void addLift(GpuTerrainLayer solid, BoardElevator.Lift lift) {
        for (var part : lift.parts().entrySet()) {
            if (part.getValue().isEmpty()) { continue; }
            var faces = part.getValue();
            String finish = switch (part.getKey()) {
                case STEEL -> "structures/common/steel";
                case HAZARD -> "structures/common/hazard-stripes";
                case CHROME -> "structures/common/chrome";
                case PLATE, PIT -> DIAMOND_PLATE;
                case PAINT -> "structures/common/safety-yellow";
                case IRON -> "structures/common/iron";
            };
            float repeat = BoardRelief.metres(switch (part.getKey()) {
                // Bold stripes a third of a metre wide, which read on the platform's band at the board's zoom.
                case STEEL, HAZARD -> 3;
                case PAINT -> 2;
                case PLATE, PIT -> PLATE_METRES;
                case IRON -> 1.5f;
                default -> 1;
            });
            boolean worn = switch (part.getKey()) {
                case PAINT, STEEL, IRON -> true;
                default -> false;
            };
            Color tint = new Color();
            // The whole board material: its normals, finish, occlusion and relief, not its colour alone.
            solid.add(assets.finish(finish, material(assets.material(finish), false)), mesh -> {
                for (var face : faces) {
                    panel(mesh, face, repeat, p -> worn ? tint.set(Color.WHITE).lerp(LIFT_GRIME, lift.wear(p)) : Color.WHITE);
                }
            });
        }
    }

    /** The diamond plate of an elevator's platform and pit, and the walls' decks (data/models/buildings/walls). */
    private static final String DIAMOND_PLATE = "structures/common/diamond-plate";
    /** The plate's repeat in metres, the walls' decks' plate size: lugs that read as diamonds, not rows. */
    private static final float PLATE_METRES = 3.2f;
    /** Paint on the plate is no metal, and duller than the bare plate. */
    private static final float PLATE_PAINT_ROUGHNESS = .65f;

    /**
     * A decal painted on an elevator's platform: its image's colour over the plate's own normals, occlusion and relief,
     * in matte paint, so the plate's lugs stand through it. Its mesh carries the plate's UVs ({@link #plateTriangles}),
     * from which the image maps by {@code uv}, the decal's unturned mapping.
     */
    private Material platePaint(Texture texture, Coords owner, megamek.common.board.BoardDecoration object,
          BoardDecals.PaintUv uv) {
        float repeat = BoardRelief.metres(PLATE_METRES);
        double x = BoardGeometry.centerX(owner) + object.x() * BoardGeometry.width();
        double y = BoardGeometry.centerY(owner) + object.y() * BoardGeometry.height();
        Material material = assets.finish(DIAMOND_PLATE, material(texture, true));
        var image = material.get(TextureAttribute.class, TextureAttribute.Diffuse);
        image.offsetU = (float) (.5 - uv.ux() * x);
        image.offsetV = (float) (.5 - uv.vy() * y);
        image.scaleU = (float) (uv.ux() * repeat);
        image.scaleV = (float) (uv.vy() * repeat);
        var plate = material.get(GpuModelMaterial.class, GpuModelMaterial.TYPE);
        material.set(new GpuModelMaterial(PLATE_PAINT_ROUGHNESS, 0, 0, null, plate.tint, plate.glow, true, plate.relief));
        return material;
    }

    /** The decal's paint {@code faces} on an elevator's platform with the plate's UVs, as {@link #panel} maps the plate. */
    private static void plateTriangles(Supplier<MeshPartBuilder> meshes, megamek.common.board.BoardDecoration object,
          List<BoardSurface.Face> faces) {
        float repeat = BoardRelief.metres(PLATE_METRES), lift = paintLift();
        String paint = object.colours().slot(0);
        Color colour = paint == null ? Color.WHITE : Color.valueOf(paint);
        for (var face : faces) {
            var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
            MeshPartBuilder.VertexInfo[] corners = new MeshPartBuilder.VertexInfo[3];
            Vector3[] points = { face.a(), face.b(), face.c() };
            for (int i = 0; i < 3; i++) {
                corners[i] = vertex(new Vector3(points[i]).add(0, 0, lift), normal, points[i].x / repeat, points[i].y / repeat,
                      colour);
            }
            meshes.get().triangle(corners[0], corners[1], corners[2]);
        }
    }

    /** The grime a Solaris lift's paint, steel and iron take where wear gathers (BoardElevator.Lift#wear). */
    private static final Color LIFT_GRIME = new Color(.5f, .41f, .32f, 1);

    private static void panel(MeshPartBuilder mesh, BoardSurface.Face face, float repeat) {
        panel(mesh, face, repeat, p -> Color.WHITE);
    }

    /**
     * A face of precast or plated work, its material mapped by the face's axis at {@code repeat} world units, each corner
     * tinted by {@code tint}. A lying face on a hex edge ({@code landEdge}), as a band along a platform's rim, runs its
     * material along that edge.
     */
    private static void panel(MeshPartBuilder mesh, BoardSurface.Face face, float repeat,
          java.util.function.Function<Vector3, Color> tint) {
        Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
        boolean lying = Math.abs(normal.z) > .7f;
        Vector3 along = lying && face.landEdge() >= 0 ? BoardGeometry.corner(new Coords(0, 0), 0, face.landEdge() + 1)
              .sub(BoardGeometry.corner(new Coords(0, 0), 0, face.landEdge())).nor() : new Vector3(1, 0, 0);
        MeshPartBuilder.VertexInfo[] corners = new MeshPartBuilder.VertexInfo[3];
        Vector3[] points = { face.a(), face.b(), face.c() };
        for (int i = 0; i < 3; i++) {
            Vector3 p = points[i];
            float u = lying ? p.x * along.x + p.y * along.y : Math.abs(normal.y) > Math.abs(normal.x) ? p.x : p.y;
            float v = lying ? p.y * along.x - p.x * along.y : -p.z;
            corners[i] = vertex(p, normal, u / repeat, v / repeat, tint.apply(p));
        }
        mesh.triangle(corners[0], corners[1], corners[2]);
    }

    private static MeshPartBuilder.VertexInfo surfaceVertex(Vector3 point, Coords coords, Vector3 normal,
          TextureRegion art, float lift) {
        Vector3 raised = new Vector3(point).add(0, 0, lift);
        if (art != null) {
            return topVertex(raised, coords, art, Color.WHITE).setNor(normal);
        }
        float repeat = 96 * BoardGeometry.hexScale();
        float u = Math.abs(normal.z) > 0.7f ? point.x / repeat
              : (Math.abs(normal.y) > Math.abs(normal.x) ? point.x : point.y) / repeat;
        float v = Math.abs(normal.z) > 0.7f ? point.y / repeat : -point.z / repeat;
        return vertex(raised, normal, u, v, Color.WHITE);
    }

    /** Whether a bank face stands above the water below dry, frozen land. Beneath the slab the bank stays bare. */
    private static boolean frozenLand(BoardScene scene, BoardScene.Tile tile, BoardSurface.Face face) {
        if (face.landEdge() < 0 || face.a().z + face.b().z + face.c().z < 3 * BoardGeometry.waterZ(tile)) { return false; }
        BoardScene.Tile land = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(face.landEdge())));
        return land != null && land.frozen() && !land.liquid().present();
    }

    /** A frozen lake's sheet, in both graphics: translucent, in the transparent pass, over what lies beneath it. */
    private Material lakeIce() {
        Material ice = GpuIceShader.lake(material(assets.ice().color(), true));
        ice.set(new Ground(1));
        return ice;
    }

    /**
     * A frozen lake as drawn. Vertex alpha is zero on the broken pieces except where they share the slab's own
     * vertices, so the ragged margin fades into broken ice across its width; red is zero on open leads.
     */

    private static void drawnIce(Supplier<MeshPartBuilder> meshes, BoardSurface surface) {
        Set<Vector3> slab = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var face : surface.iceSlab) {
            slab.addAll(List.of(face.a(), face.b(), face.c()));
            iceTriangle(meshes.get(), face, point -> Color.WHITE);
        }
        Color broken = new Color(1, 1, 1, 0), lead = new Color(0, 1, 1, 0);
        for (var face : surface.iceBroken) {
            iceTriangle(meshes.get(), face, point -> slab.contains(point) ? Color.WHITE : broken);
        }
        for (var face : surface.iceLeads) { iceTriangle(meshes.get(), face, point -> lead); }
    }

    private static void iceTriangle(MeshPartBuilder mesh, BoardSurface.Face face, Function<Vector3, Color> colors) {
        iceTriangle(mesh, face, colors, Map.of());
    }

    private static void iceTriangle(MeshPartBuilder mesh, BoardSurface.Face face, Function<Vector3, Color> colors,
          Map<Vector3, Vector3> normals) {
        iceTriangle(mesh, face, colors, normals, null, 0);
    }

    private static void iceTriangle(MeshPartBuilder mesh, BoardSurface.Face face, Function<Vector3, Color> colors,
          Map<Vector3, Vector3> normals, float[] uv, int offset) {
        Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
        float repeat = 96 * BoardGeometry.hexScale();
        MeshPartBuilder.VertexInfo[] corners = new MeshPartBuilder.VertexInfo[3];
        Vector3[] points = { face.a(), face.b(), face.c() };
        for (int i = 0; i < 3; i++) {
            float u = uv == null ? points[i].x / repeat : uv[offset + i * 2];
            float v = uv == null ? points[i].y / repeat : uv[offset + i * 2 + 1];
            corners[i] = vertex(points[i], normals.getOrDefault(points[i], normal), u, v, colors.apply(points[i]));
        }
        mesh.triangle(corners[0], corners[1], corners[2]);
    }

    private BoundingBox unitBounds(ModelInstance unit) {
        return frameBounds == null ? UnitBounds.world(unit) : frameBounds.get(unit);
    }

    /** Unit instances already contain the shared animation's current position; hidden units never enter this list. */
    void animate(float delta, List<ModelInstance> units) {
        animate(delta, units, DEFAULT_BUILDING_OPACITY);
    }

    void animate(float delta, List<ModelInstance> units, float buildingAlpha) {
        animate(delta, units, buildingAlpha, null, Float.NaN);
    }

    void animate(float delta, List<ModelInstance> units, float buildingAlpha, Coords hovered, float hoverFloorZ) {
        float nextBuilding = MathUtils.clamp(buildingAlpha, 0, 1);
        boolean changedOpacity = nextBuilding != buildingOpacity;
        buildingOpacity = nextBuilding;
        clock += delta;
        magmaClock += delta * (float) Math.sqrt(gravity / BoardAtmosphere.STANDARD_GRAVITY);
        // A shared, continuous gust clock: stronger wind moves faster without rephasing when the slider changes.
        vegetationPhase = (vegetationPhase + delta * (.65f + 3.35f * wind.z)) % MathUtils.PI2;
        // The wave simulations run only for fields to show: none on Classic chunks, none in the Tactical View.
        boolean lava = chunks.stream().anyMatch(chunk -> chunk.lavaField != null);
        if (lava && !fluffHidden) { lavaOcean.update(clock, wind, gravity); }
        else if (!lava && lavaOcean.texture() != null) { lavaOcean.reset(); }
        boolean water = proceduralWater && chunks.stream().anyMatch(chunk -> chunk.waterField != null);
        if (water && waterEffects && !fluffHidden) {
            ocean.update(clock, wind, gravity);
            waders.update(coverScene, units, units.stream().map(this::unitBounds).toList(), delta);
        } else if (!water && ocean.texture() != null) { ocean.reset(); }
        List<BoundingBox> occupied = hasCutaways && buildingOpacity < 1
              ? units.stream().map(this::unitBounds).toList() : List.of();
        BoardScene.Tile hoverTile = coverScene == null || hovered == null ? null : coverScene.tile(hovered);
        // The roof is the boundary above the last interior floor, never a hover cutaway target.
        var hoverBuilding = buildingOpacity < 1 && hoverTile != null ? hoverTile.features().stream().filter(feature ->
              feature.kind() == BoardScene.FeatureKind.BUILDING
                    && !BoardWall.building(hoverTile.cover(), feature)
                    && hoverFloorZ >= (hoverTile.elevation() + feature.elevation()) * BoardGeometry.level()
                    && hoverFloorZ < (hoverTile.elevation() + feature.elevation() + feature.height()) * BoardGeometry.level())
              .findFirst().orElse(null) : null;
        float hoverCeiling = hoverFloorZ + BoardGeometry.level();
        if (hoverBuilding != null && hoverFloorZ >= (hoverTile.elevation() + hoverBuilding.elevation()
              + hoverBuilding.height() - 1) * BoardGeometry.level()) {
            // The top storey's cutaway includes its entire roof cap, including any trim above the nominal height.
            hoverCeiling = Float.MAX_VALUE;
        }
        for (Chunk chunk : chunks) {
            chunk.liquidMaterials.forEach(this::animateLiquid);
            if (chunk.cutaways.isEmpty()) { continue; }
            Coords hoverCoords = hoverBuilding != null && chunk.tileMeshes.containsKey(hovered) ? hovered : null;
            boolean unitsHere = false;
            for (BoundingBox unit : occupied) { unitsHere |= chunk.bounds.intersects(unit); }
            // Without units, a hovered interior or faded props, nothing in this chunk fades or returns.
            if (!unitsHere && !changedOpacity && hoverCoords == null && chunk.hoverCoords == null && chunk.faded.isEmpty()) {
                continue;
            }
            Map<Coords, Float> occupiedHexes = new HashMap<>();
            for (BoundingBox unit : occupied) {
                if (!chunk.bounds.intersects(unit)) {
                    continue;
                }
                for (Prop prop : chunk.cutaways) {
                    if (!prop.industrial && Float.isNaN(prop.floorZ) && prop.bounds().intersects(unit)) {
                        occupiedHexes.merge(prop.coords(), unit.min.z, Math::min);
                    }
                }
            }
            float hoverFloor = hoverCoords == null ? Float.NaN : hoverFloorZ;
            boolean changedHover = !java.util.Objects.equals(hoverCoords, chunk.hoverCoords)
                  || Float.compare(hoverFloor, chunk.hoverFloor) != 0;
            chunk.hoverCoords = hoverCoords;
            chunk.hoverFloor = hoverFloor;
            // One fade rule for every prop: a placed object fades with a hovered or occupied structure like a packed prop;
            // only route markers never fade. Neither Chunk#cacheProps(omitFaded) nor #addDecorations draws a faded prop
            // opaque: renderTransparent draws it.
            Set<Prop> faded = new HashSet<>();
            if (!occupiedHexes.isEmpty()) {
                for (Prop prop : chunk.cutaways) {
                    Float lowest = occupiedHexes.get(prop.coords());
                    boolean hoverSupport = prop.coords.equals(hoverCoords)
                          && Math.abs(prop.floorZ - hoverFloor) < .05f * BoardGeometry.hexScale();
                    if (!prop.route() && !prop.industrial && lowest != null && (Float.isNaN(prop.floorZ)
                          || prop.floorZ > lowest + .05f * BoardGeometry.hexScale() && !hoverSupport)) {
                        faded.add(prop);
                    }
                }
            }
            if (hoverCoords != null) {
                for (Prop prop : chunk.cutaways) {
                    if (!prop.route() && !prop.industrial && Float.isNaN(prop.floorZ) && prop.coords.equals(hoverCoords)) { faded.add(prop); }
                }
            }
            boolean changedOccupancy = !faded.equals(chunk.faded) || changedHover || chunk.cutaways.stream().anyMatch(prop ->
                  !prop.route() && !prop.industrial && Float.isNaN(prop.floorZ) && prop.coords.equals(hoverCoords)
                        && (prop.hoverOpaque != null) != !occupiedHexes.containsKey(prop.coords));
            if (changedOccupancy || changedOpacity) {
                scarSurfaceRevision++;
                Set<Prop> ordered = new java.util.LinkedHashSet<>();
                for (Prop prop : chunk.cutaways) { if (faded.contains(prop)) { ordered.add(prop); } }
                chunk.faded = Collections.unmodifiableSet(ordered);
                for (Prop prop : chunk.cutaways) {
                    if (prop.route()) { continue; }
                    boolean hoverShell = !prop.industrial && Float.isNaN(prop.floorZ) && prop.coords.equals(hoverCoords);
                    boolean hoverOnly = hoverShell && !occupiedHexes.containsKey(prop.coords);
                    for (Material material : prop.instance().materials) {
                        if (hoverOnly) {
                            material.set(new GpuBuildingCutaway(hoverFloor, hoverCeiling, true));
                        } else { material.remove(GpuBuildingCutaway.TYPE); }
                        if (faded.contains(prop)) {
                            BlendingAttribute blend = material.get(BlendingAttribute.class, BlendingAttribute.Type);
                            if (blend == null) {
                                blend = new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
                                material.set(blend);
                                material.set(new DepthTestAttribute(GL20.GL_LEQUAL, false));
                                culling(prop.instance(), material, true);
                            }
                            blend.opacity = Float.isNaN(prop.floorZ) ? buildingOpacity
                                  : MathUtils.lerp(buildingOpacity, 1, .25f);
                        } else {
                            material.remove(BlendingAttribute.Type);
                            material.remove(DepthTestAttribute.Type);
                            culling(prop.instance(), material, false);
                        }
                    }
                    if (hoverOnly) { prop.updateHoverOpaque(); }
                    else { prop.hoverOpaque = null; }
                }
                for (InteriorStruts strut : chunk.struts) {
                    for (Material material : strut.instance.materials) {
                        if (strut.coords.equals(hoverCoords) && !occupiedHexes.containsKey(strut.coords)) {
                            material.set(new GpuBuildingCutaway(hoverFloor, hoverFloor + BoardGeometry.level(), true));
                        } else { material.remove(GpuBuildingCutaway.TYPE); }
                    }
                }
                if (changedOccupancy) {
                    chunk.cacheProps();
                }
            }
        }
    }

    Environment environment() {
        return environment;
    }

    /** A borrowed section view meters the environment actually published by applyLight, including manual light. */
    float lightAdaptation() {
        var ambient = environment.get(ColorAttribute.class, ColorAttribute.AmbientLight);
        Color ground = new Color(ambient == null ? Color.BLACK : ambient.color);
        var directions = environment.get(DirectionalLightsAttribute.class, DirectionalLightsAttribute.Type);
        if (directions != null) {
            for (var source : directions.lights) {
                float incidence = BoardAtmosphere.meteringIncidence(source.direction);
                ground.r += source.color.r * incidence;
                ground.g += source.color.g * incidence;
                ground.b += source.color.b * incidence;
            }
        }
        return BoardAtmosphere.adaptation(ground);
    }

    BoundingBox roofBounds(Coords coords) {
        BoundingBox result = null;
        int index = (coords.getX() / CHUNK_SIZE) * chunkRows + coords.getY() / CHUNK_SIZE;
        if (index < 0 || index >= chunks.size()) {
            return null;
        }
        var tile = chunks.get(index).tileMeshes.get(coords);
        if (tile != null && tile.bridgeShape != null) { result = new BoundingBox(tile.bridgeShape.bounds()); }
        for (Prop prop : chunks.get(index).props) {
            if (prop.coords().equals(coords) && tile != null && tile.bridgeShape != null) {
                result.ext(prop.bounds());
            } else if (prop.coords().equals(coords)
                  && (result == null || prop.bounds().max.z > result.max.z)) {
                result = prop.bounds();
            }
        }
        return result;
    }

    /** Pointer picks pass through foliage and industrial cover; occupiable structures retain their authored mesh. */
    Coords pick(BoardScene scene, Ray ray) {
        BoardGeometry.Hit hit = selectionHit(scene, ray);
        return hit == null ? null : hit.coords();
    }

    record DecorationHit(Coords coords, String id, float distance, float anchorZ) { }

    /**
     * A drawn object: its box, its anchor's level and world point, the placement it is drawn with (drags included),
     * whether this zoom shows it ({@link #drawn}), and whether it is a decal's paint.
     */
    record EditorObject(String id, BoundingBox bounds, float anchorLevel, Vector3 anchor,
          megamek.common.board.BoardDecoration.Placement placement, boolean shown, boolean decal) { }

    private record EditorPlacement(Coords coords, BoardScene.Feature feature) { }

    /** A copy of a placement's instance; a placed object's colours (its userData) travel with it. */
    private static ModelInstance copy(ModelInstance source) {
        ModelInstance result = new ModelInstance(source);
        result.userData = source.userData;
        return result;
    }
    /** Borrowed instances that crossed an owner boundary before the replacement chunks finish. */
    private Map<Coords, List<Prop>> editorMovedProps = Map.of();
    /** Installed chunks replaced props since editorMovedProps was derived. */
    private boolean editorPropsInstalled;

    private TileMesh editorTile(Coords coords) {
        if (coords == null || chunkRows == 0) { return null; }
        int index = (coords.getX() / CHUNK_SIZE) * chunkRows + coords.getY() / CHUNK_SIZE;
        return index < 0 || index >= chunks.size() ? null : chunks.get(index).tileMeshes.get(coords);
    }

    private List<Prop> editorProps(Coords coords) {
        TileMesh tile = editorTile(coords);
        if (tile == null) { return List.of(); }
        List<Prop> moved = editorMovedProps.getOrDefault(coords, List.of());
        if (moved.isEmpty() && tile.props.stream().allMatch(prop -> coords.equals(prop.coords()))) { return tile.props; }
        List<Prop> result = new ArrayList<>(moved);
        tile.props.stream().filter(prop -> coords.equals(prop.coords())).forEach(result::add);
        return result;
    }

    /**
     * Show edits to existing model placements as soon as the EDT publishes them. All views, picks and shadow passes
     * borrow these same instances. Their support is the currently drawn terrain; a completed chunk replaces the
     * temporary pose and its vegetation together. Neither the document nor pending build inputs are mutated here.
     */
    void previewEditorObjects(BoardScene scene) {
        if (!ready(scene)) { return; }
        installedSettings.run(() -> {
            // Chunks usually share the tiles they were last shown with: one comparison serves them all.
            Map<List<BoardScene.Tile>, Map<String, EditorPlacement>> changes = new IdentityHashMap<>();
            boolean movedProps = editorPropsInstalled;
            editorPropsInstalled = false;
            for (Chunk chunk : chunks) {
                if (chunk.editorTiles == scene.tiles()) { continue; }
                var placements = changes.computeIfAbsent(chunk.editorTiles, shown -> changedPlacements(shown, scene.tiles()));
                chunk.editorTiles = scene.tiles();
                boolean changed = false, trees = false;
                List<Prop> candidates = new ArrayList<>();
                for (String id : placements.size() < chunk.objectProps.size() ? placements.keySet() : chunk.objectProps.keySet()) {
                    if (placements.containsKey(id)) { candidates.addAll(chunk.objectProps.getOrDefault(id, List.of())); }
                }
                for (Prop prop : candidates) {
                    // Route shape and placement commit together after the adjacent tiles rebuild.
                    if (BoardRail.route(prop.decorationFeature)) { continue; }
                    var placement = placements.get(prop.decorationId);
                    BoardScene.Feature next = placement == null ? prop.decorationFeature : placement.feature();
                    Coords coords = placement == null ? prop.coords : placement.coords();
                    // Another model or other colours need the chunk's rebuild, which installs that model.
                    if (!prop.decorationFeature.asset().equals(next.asset())
                          || !prop.decorationFeature.colours().equals(next.colours())) { continue; }
                    if (next.equals(prop.previewFeature == null ? prop.decorationFeature : prop.previewFeature)
                          && coords.equals(prop.coords())) { continue; }
                    ModelInstance preview = null;
                    float z = 0;
                    if (!next.equals(prop.decorationFeature) || !coords.equals(prop.coords)) {
                        z = editorHeight(coords, next);
                        if (!Float.isFinite(z)) { continue; }
                        preview = copy(prop.instance);
                        placeDecoration(preview, coords, next, z + next.elevation() * BoardGeometry.level());
                    }
                    var oldGeyser = GpuGeysers.emitter(prop.decorationFeature.asset(), prop.instance().transform,
                          GpuGeysers.vent(prop.instance().model));
                    prop.previewFeature = next;
                    prop.previewCoords = coords;
                    prop.previewInstance = preview;
                    prop.previewBounds = preview == null ? null : preview.calculateBoundingBox(new BoundingBox()).mul(preview.transform);
                    prop.previewAnchorZ = z;
                    if (oldGeyser != null) {
                        chunk.geysers.remove(oldGeyser);
                        var geyser = GpuGeysers.emitter(next.asset(), prop.instance().transform,
                              GpuGeysers.vent(prop.instance().model));
                        chunk.geysers.add(geyser);
                        if (preview != null) { geyserBounds(prop.previewBounds, geyser); }
                    }
                    // Keep both the installed terrain and the moved object's envelope until the rebuild commits.
                    chunk.bounds.ext(prop.bounds());
                    shadowBounds.ext(prop.bounds());
                    changed = true;
                    trees |= prop.tree();
                }
                if (changed) {
                    scarSurfaceRevision++;
                    if (trees) { chunk.restand(); }
                    chunk.cacheDecorations();
                    refreshShadows();
                    chunk.previewed = true;
                    movedProps = true;
                }
            }
            if (movedProps) {
                Map<Coords, List<Prop>> moved = new HashMap<>();
                for (Chunk chunk : chunks) {
                    if (!chunk.previewed) { continue; }
                    for (Prop prop : chunk.props) {
                        if (!prop.coords.equals(prop.coords())) {
                            moved.computeIfAbsent(prop.coords(), key -> new ArrayList<>()).add(prop);
                        }
                    }
                }
                editorMovedProps = moved;
            }
        });
    }

    /**
     * The height an editor placement of {@code feature} in {@code coords} stands at on the installed terrain, before its
     * elevation: its support beneath its anchor ({@link #decorationHeight}) as the drawn chunks have it; not finite where
     * nothing supports it. The drag preview ({@link #previewEditorObjects}) and the paint preview's ghosts
     * ({@link #editorGhost}) place by it. The caller holds the installed settings.
     */
    private float editorHeight(Coords coords, BoardScene.Feature feature) {
        var object = feature.decoration();
        float x = BoardGeometry.centerX(coords) + (float) object.x() * BoardGeometry.width();
        float y = BoardGeometry.centerY(coords) + (float) object.y() * BoardGeometry.height();
        var footprint = BoardGeometry.tile(coverScene, x, y);
        Coords supportCoords = footprint == null ? coords : footprint.coords();
        Chunk receiving = chunks.get(supportCoords.getX() / CHUNK_SIZE * chunkRows + supportCoords.getY() / CHUNK_SIZE);
        var support = tacticalSurface(coverScene, receiving, supportCoords, floor);
        float groundZ = BoardSurface.sampleHeight(support.faces(), x, y, BoardGeometry.groundZ(coverScene.tile(supportCoords)));
        TileMesh mesh = receiving.tileMeshes.get(supportCoords);
        return decorationHeight(coverScene, feature, groundZ, support.faces(), mesh.bridgeShape, mesh.props, x, y,
              coverScene.tile(supportCoords));
    }

    /**
     * The editor preview's ghost of the placed object {@code feature} of {@code coords} (GpuEditorPreview): its model in
     * its colours, placed as a moved object's drag preview is ({@link #editorHeight}); null off the installed board or
     * where nothing supports it. The model is the assets' own; the instance is the caller's.
     */
    ModelInstance editorGhost(Coords coords, BoardScene.Feature feature) {
        if (editorTile(coords) == null) { return null; }
        return installedSettings.call(() -> {
            float z = editorHeight(coords, feature);
            if (!Float.isFinite(z)) { return null; }
            ModelInstance ghost = new ModelInstance(assets.decoration(feature.asset(), feature.colours()));
            placeDecoration(ghost, coords, feature, z + feature.elevation() * BoardGeometry.level());
            return ghost;
        });
    }

    /**
     * A ghost of the authored scenery {@code feature} of {@code coords}, such as the sandbags of a hex being fortified
     * (GpuEditorPreview#work): its model placed as the chunk builder places authored scenery, on the installed hex's
     * ground at its centre; null off the installed board. The model is the assets' own; the instance is the caller's.
     */
    ModelInstance sceneryGhost(Coords coords, BoardScene.Feature feature) {
        if (editorTile(coords) == null) { return null; }
        return installedSettings.call(() -> {
            float x = BoardGeometry.centerX(coords), y = BoardGeometry.centerY(coords);
            Chunk receiving = chunks.get(coords.getX() / CHUNK_SIZE * chunkRows + coords.getY() / CHUNK_SIZE);
            var support = tacticalSurface(coverScene, receiving, coords, floor);
            BoardScene.Tile tile = coverScene.tile(coords);
            float base = BoardGroundCut.base(support.faces(), x, y, BoardGeometry.groundZ(tile));
            ModelInstance ghost = new ModelInstance(assets.model(feature.asset(), BoardGroundCut.colours(tile, feature)));
            BoardGroundCut.showParts(ghost, feature.parts());
            placeAuthored(ghost, x + feature.x() * BoardGeometry.hexScale(), y + feature.y() * BoardGeometry.hexScale(),
                  base, BoardGroundCut.fitted(coverScene, tile, feature));
            return ghost;
        });
    }

    /** Components share their composition's supporting plane {@code base} and keep their local origins and offsets. */
    private static void placeAuthored(ModelInstance instance, float x, float y, float base, BoardScene.Feature feature) {
        instance.transform.set(BoardFeatures.authoredTransform(x, y, base, feature));
    }

    /** Infrastructure uses exactly the same feature collection and placement in a preview and a committed chunk. */
    static boolean routeFeature(BoardScene.Tile tile, BoardScene.Feature feature) {
        return feature.asset().equals("bridge") || BoardWall.building(tile.cover(), feature) || BoardRail.route(feature)
              || feature.decoration() != null && BoardFeatures.terrainOwned(feature.decoration())
                    && (feature.asset().startsWith("scenery/road/") || feature.asset().startsWith("scenery/rail/"));
    }

    private Model routeModel(ChunkBuild build, BoardRail.Layout layout) {
        if (build.preview == null) { return assets.route(layout); }
        Model model = new Model(BoardRail.model(layout));
        build.preview.owned.add(model);
        return model;
    }

    /**
     * Pending infrastructure of the tiles an editor event changes ({@link #previewRoutes}): each replaced tile's pieces
     * as the committed builders make them in the pending scene, or, where it keeps none, its installed pieces that go.
     * Owns generated meshes and mask textures, borrows authored models, and hides only the installed pieces of the tiles
     * it replaces. No document or chunk is changed. The caller disposes this before replacing the preview.
     */
    final class EditorRoutes implements Disposable {
        final Map<Coords, List<ModelInstance>> ghosts = new LinkedHashMap<>();
        /** The replaced tiles. */
        final Set<Coords> coords = new HashSet<>();
        /** The replaced tiles whose ghosts are the installed pieces that go. */
        final Set<Coords> removed = new HashSet<>();
        final List<Model> owned = new ArrayList<>();
        final List<Chunk> carriers = new ArrayList<>();
        private final Map<BoardScene.Pixels, Texture> masks = new HashMap<>();
        private final Map<Chunk, Hidden> hidden = new IdentityHashMap<>();

        /** A draped triangle extends past its mask. Clamp that mask alone, never into an adjacent atlas slot. */
        Texture mask(BoardScene.Pixels pixels) {
            return masks.computeIfAbsent(pixels, image -> {
                Pixmap pixmap = GpuTextures.pixmap(image, 0);
                try {
                    Texture texture = new Texture(pixmap);
                    texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
                    texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
                    return texture;
                } finally { pixmap.dispose(); }
            });
        }

        /** What {@code chunk} draws in place of its caches while this preview replaces some of its tiles. */
        Hidden hidden(Chunk chunk) {
            return hidden.computeIfAbsent(chunk, ignored -> {
                List<TileRange> ranges = new ArrayList<>();
                boolean props = false;
                for (Coords at : coords) {
                    TileMesh mesh = chunk.tileMeshes.get(at);
                    if (mesh == null) { continue; }
                    mesh.ranges.stream().filter(TileRange::route).forEach(ranges::add);
                    props |= mesh.props.stream().anyMatch(prop -> prop.infrastructure);
                }
                return new Hidden(without(chunk.opaque, ranges, 0), without(chunk.overlays, ranges, 2), null, props);
            });
        }

        @Override public void dispose() {
            if (editorRoutes == this) { editorRoutes = null; refreshShadows(); }
            carriers.forEach(Chunk::dispose);
            owned.forEach(Model::dispose);
            masks.values().forEach(Texture::dispose);
            assets.release(this);
            hidden.clear();
        }
    }

    /**
     * A chunk's draws while an editor preview replaces some of its tiles: its opaque and overlay layers without the
     * replaced roads and decks (null where it replaces none in that layer), and whether it replaces any of its props,
     * which it then draws one by one.
     */
    private record Hidden(Array<Renderable> ground, Array<Renderable> overlays, Array<Renderable> paint, boolean props) {
        static final Hidden NONE = new Hidden(null, null, null, false);
    }

    /** An editor board plays no scene changes, so moving lifts and an editor preview never mask one chunk together. */
    private Hidden hidden(Chunk chunk) {
        return lifts != null ? lifts.hidden(chunk) : editorRoutes == null ? Hidden.NONE : editorRoutes.hidden(chunk);
    }

    /**
     * Presents the scene change the playback plays ({@link UnitPlayback#transition}) at {@code progress}, each frame
     * before the terrain updates; null while none plays. While it plays, the terrain builds each hex it moves at the
     * lower of its two heights, so that the ground beside it keeps its walls down to there; once it ends, its platforms
     * stay at their end until the terrain has installed its after tiles. Classic columns snap.
     */
    void present(BoardScene.TileTransition transition, float progress) {
        boolean shown = !installedColumns && coverScene != null;
        if (lifts != null && (!shown || (transition != null ? lifts.transition != transition
              : heights(lifts.transition, BoardScene.TileTransition.Change::after)))) {
            lifts.dispose();
            lifts = null;
            refreshShadows();
        }
        if (!shown || transition == null && lifts == null) { return; }
        if (lifts == null) {
            lifts = new Lifts(transition);
            refreshShadows();
        }
        lifts.ending = transition == null;
        lifts.move(lifts.ending ? 1 : progress);
    }

    /**
     * Whether the terrain shows each hex {@code transition} moves at its lower height, from which its platform moves;
     * Classic columns need nothing.
     */
    boolean ready(BoardScene.TileTransition transition) {
        return installedColumns || heights(transition, Lifts::lower);
    }

    private boolean heights(BoardScene.TileTransition transition, java.util.function.Function<BoardScene.TileTransition.Change,
          BoardScene.Tile> shown) {
        return coverScene == null || transition.changes().stream().allMatch(change -> {
            var tile = coverScene.tile(change.coords());
            return tile == null || tile.elevation() == shown.apply(change).elevation();
        });
    }

    /** How far the moving elevator platform at {@code coords} stands from its after height; 0 where none moves. */
    float liftOffset(Coords coords) { return lifts == null ? 0 : lifts.offset(coords); }

    /**
     * The raised Solaris elevators a scene change moves, between the heights of their before and after tiles. Each
     * moving hex's top and lift leave its chunk's draws; its top draws moved to the platform's height, on a lift built
     * at that height. Presentation only: picking and ground support follow the installed tiles, each moving hex at its
     * lower height until the change has played. Owns the lifts it builds; the chunks' meshes stay theirs.
     */
    private final class Lifts implements Disposable {
        private record Top(Chunk chunk, Array<Renderable> ground, Array<Renderable> overlays, Array<Renderable> paint) { }
        final BoardScene.TileTransition transition;
        /** Each moving hex's walls at its higher height, down to the ground beside it. */
        private final Map<Coords, List<BoardSurface.Face>> walls = new HashMap<>();
        /** Each moving platform's height this frame. */
        private final Map<Coords, Float> heights = new HashMap<>();
        private final Map<Coords, Top> tops = new HashMap<>();
        private final Map<Chunk, Hidden> hidden = new IdentityHashMap<>();
        /** The lifts at this frame's heights; they cast shadows like units, over the cached terrain's. */
        final List<ModelInstance> models = new ArrayList<>();
        /** The change has played; its platforms wait at their end for the terrain's after tiles. */
        boolean ending;
        private List<BoardScene.Tile> loweredSource;
        private List<BoardScene.Tile> lowered;

        Lifts(BoardScene.TileTransition transition) {
            this.transition = transition;
            installedSettings.run(() -> {
                for (var change : transition.changes()) {
                    var high = change.before().elevation() > change.after().elevation() ? change.before() : change.after();
                    var surface = new BoardSurface(coverScene, high);
                    walls.put(change.coords(), surface.straightWalls(surface.sides(coverScene, floor)));
                }
            });
        }

        /** The platforms at {@code progress} of the change, eased in and out, and their lifts at those heights. */
        void move(float progress) {
            float eased = progress * progress * (3 - 2 * progress);
            boolean moved = false;
            for (var change : transition.changes()) {
                float top = MathUtils.lerp(BoardGeometry.surfaceZ(change.before()), BoardGeometry.surfaceZ(change.after()), eased);
                Float previous = heights.put(change.coords(), top);
                moved |= previous == null || previous != top;
            }
            if (!moved) { return; }
            disposeModels();
            GpuTerrainLayer layer = new GpuTerrainLayer();
            installedSettings.run(() -> {
                for (var change : transition.changes()) {
                    addLift(layer, BoardElevator.build(coverScene, change.coords(), heights.get(change.coords()),
                          walls.get(change.coords())));
                }
            });
            layer.finish(models);
        }

        static BoardScene.Tile lower(BoardScene.TileTransition.Change change) {
            return change.before().elevation() < change.after().elevation() ? change.before() : change.after();
        }

        /** {@code scene}'s tiles with each moving hex at its lower height; the same list for the same tiles. */
        List<BoardScene.Tile> lowered(BoardScene scene) {
            if (scene.tiles() != loweredSource) {
                List<BoardScene.Tile> tiles = new ArrayList<>(scene.tiles());
                for (var change : transition.changes()) {
                    int index = tiles.indexOf(scene.tile(change.coords()));
                    if (index >= 0) { tiles.set(index, lower(change)); }
                }
                loweredSource = scene.tiles();
                lowered = List.copyOf(tiles);
            }
            return lowered;
        }

        /** {@code chunk}'s draws without the moving hexes' tops and lifts. */
        Hidden hidden(Chunk chunk) {
            return hidden.computeIfAbsent(chunk, ignored -> {
                List<TileRange> ranges = new ArrayList<>();
                for (var change : transition.changes()) {
                    TileMesh mesh = chunk.tileMeshes.get(change.coords());
                    if (mesh != null) { ranges.addAll(mesh.ranges); }
                }
                return ranges.isEmpty() ? Hidden.NONE
                      : new Hidden(without(chunk.opaque, ranges, 0), without(chunk.overlays, ranges, 2),
                            without(chunk.authoredPaint, ranges, 6), false);
            });
        }

        /** The moving hexes' tops, as their chunks draw them without their lifts, moved to their platforms' heights. */
        private List<Top> tops() {
            List<Top> result = new ArrayList<>();
            for (var change : transition.changes()) {
                Coords at = change.coords();
                Chunk chunk = chunks.get(at.getX() / CHUNK_SIZE * chunkRows + at.getY() / CHUNK_SIZE);
                Top top = tops.get(at);
                if (top == null || top.chunk() != chunk) {
                    TileMesh mesh = chunk.tileMeshes.get(at);
                    var kept = mesh == null ? List.<TileRange>of() : mesh.ranges.stream().filter(range -> !range.lift()).toList();
                    top = new Top(chunk, within(chunk.opaque, kept, 0), within(chunk.overlays, kept, 2),
                          within(chunk.authoredPaint, kept, 6));
                    tops.put(at, top);
                }
                float rise = heights.get(at) - BoardGeometry.surfaceZ(coverScene.tile(at));
                for (Renderable part : top.ground()) { part.worldTransform.setToTranslation(0, 0, rise); }
                for (Renderable part : top.overlays()) { part.worldTransform.setToTranslation(0, 0, rise); }
                for (Renderable part : top.paint()) { part.worldTransform.setToTranslation(0, 0, rise); }
                result.add(top);
            }
            return result;
        }

        void render(ModelBatch target, Environment lighting) {
            for (Top top : tops()) { renderRetained(top.ground(), target, lighting); }
            for (ModelInstance model : models) { target.render(model, lighting); }
        }

        /** {@code paint}: the hexes' authored paint (elevator plates), which the Tactical View hides. */
        void renderOverlays(ModelBatch target, Environment lighting, boolean paint) {
            for (Top top : tops()) {
                renderRetained(top.overlays(), target, lighting);
                if (paint) { renderRetained(top.paint(), target, lighting); }
            }
        }

        float offset(Coords coords) {
            for (var change : transition.changes()) {
                if (change.coords().equals(coords)) { return heights.get(coords) - BoardGeometry.surfaceZ(change.after()); }
            }
            return 0;
        }

        private void disposeModels() {
            models.forEach(model -> model.model.dispose());
            models.clear();
        }

        @Override public void dispose() {
            disposeModels();
            tops.clear();
            hidden.clear();
        }
    }

    /** The draws of {@code instances} without {@code removed}'s ranges in {@code layer}; the meshes and materials stay theirs. */
    private static Array<Renderable> without(List<ModelInstance> instances, List<TileRange> removed, int layer) {
        var ranges = removed.stream().filter(range -> range.layer() == layer)
              .sorted(Comparator.comparingInt(TileRange::offset)).toList();
        if (ranges.isEmpty()) { return null; }
        Array<Renderable> result = new Array<>();
        for (Renderable source : GpuTerrainDepth.snapshot(instances)) {
            int start = source.meshPart.offset, end = start + source.meshPart.size;
            for (TileRange range : ranges) {
                if (range.mesh() != source.meshPart.mesh || range.offset() >= end || range.offset() + range.count() <= start) { continue; }
                append(result, source, start, Math.max(start, range.offset()));
                start = Math.min(end, range.offset() + range.count());
            }
            append(result, source, start, end);
        }
        return result;
    }

    /** The draws of {@code instances} within {@code kept}'s ranges in {@code layer}; the meshes and materials stay theirs. */
    private static Array<Renderable> within(List<ModelInstance> instances, List<TileRange> kept, int layer) {
        Array<Renderable> result = new Array<>();
        for (Renderable source : GpuTerrainDepth.snapshot(instances)) {
            int start = source.meshPart.offset, end = start + source.meshPart.size;
            for (TileRange range : kept) {
                if (range.layer() == layer && range.mesh() == source.meshPart.mesh) {
                    append(result, source, Math.max(start, range.offset()), Math.min(end, range.offset() + range.count()));
                }
            }
        }
        return result;
    }

    private static void append(Array<Renderable> result, Renderable source, int start, int end) {
        if (start >= end) { return; }
        Renderable part = new Renderable().set(source);
        part.meshPart.offset = start;
        part.meshPart.size = end - start;
        result.add(part);
    }

    /** A road coat of the route pieces: a road's, a deck's or a tunnel floor's; other coats are the ground's. */
    private static boolean routeCoat(RoadPatch patch) { return patch.flat() || patch.source().road() != BoardRoad.Kind.NONE; }

    /** {@code at}'s road coats in {@code side}, as {@link #roads} draws them. */
    private static List<RoadPatch> coats(RouteSide side, Coords at) {
        return side.infrastructure().roads().getOrDefault(at, List.of()).stream().filter(GpuTerrain::routeCoat).toList();
    }

    /**
     * Whether two road coats of {@code at} are drawn alike ({@link #roads}): the same material, finish and triangles, and
     * the same mask texels under those triangles, which hold the tint and fading. Paint of a shared mask outside them
     * is not drawn there.
     */
    private static boolean sameCoat(Coords at, RoadPatch was, RoadPatch shown) {
        var before = was.patch();
        var after = shown.patch();
        if (was.flat() != shown.flat() || !before.texture().equals(after.texture()) || before.repeat() != after.repeat()
              || !GpuRoads.attribute(before).equals(GpuRoads.attribute(after))
              || !Objects.equals(GpuRoads.material(was.source(), was.flat(), before.texture()),
                    GpuRoads.material(shown.source(), shown.flat(), after.texture()))
              || !was.triangles().equals(shown.triangles())) { return false; }
        return was.mask().equals(shown.mask()) || Objects.equals(GpuRoads.visibleMask(at, was.mask(), was.triangles()),
              GpuRoads.visibleMask(at, shown.mask(), shown.triangles()));
    }

    private static boolean sameBridge(BoardBridge.Shape before, BoardBridge.Shape after) {
        return before == after || before != null && after != null && before.surface() == after.surface()
              && before.level() == after.level() && before.facets().equals(after.facets());
    }

    /** {@code scene}'s surfaces and shared infrastructure of {@code coords}, as a chunk at {@code lod} prepares them. */
    private record RouteSide(BoardScene scene, Map<Coords, BoardSurface> surfaces, Infrastructure infrastructure) {
        static RouteSide of(BoardScene scene, List<Coords> coords, TerrainLod lod, TerrainSettings settings, boolean columns) {
            Map<Coords, BoardSurface> surfaces = new ConcurrentHashMap<>();
            var tiles = coords.stream().map(scene::tile).toList();
            tiles.parallelStream().forEach(tile -> settings.run(() -> surfaces.put(tile.coords(),
                  columns ? BoardSurface.columns(scene, tile, lod) : new BoardSurface(scene, tile, lod))));
            return new RouteSide(scene, surfaces, prepareInfrastructure(scene, tiles, lod, settings, surfaces, () -> { },
                  new TerrainLoadProgress(), new GpuRoads.Masks(), columns));
        }
    }

    /** Whether {@code at} can hold route pieces in {@code scene}: its own, or road coats reaching it from a neighbour. */
    private static boolean routed(BoardScene scene, Coords at) {
        var tile = scene.tile(at);
        if (tile.features().stream().anyMatch(feature -> routeFeature(tile, feature))) { return true; }
        for (int direction = -1; direction < 6; direction++) {
            var source = direction < 0 ? tile : scene.neighbor(tile, direction);
            if (source != null && (BoardRoad.rendered(source) || BoardRoad.submerged(source))) { return true; }
        }
        return false;
    }

    /** Both sides of the route pieces of the routed candidates ({@link #routed}) at {@code lod}, and those that differ. */
    record RouteChange(TerrainLod lod, RouteSide was, RouteSide shown, List<Coords> changed) { }

    /**
     * Of {@code candidates}, those whose route pieces the committed builders make differently in {@code after} than in
     * {@code before} at {@code lod}: walls, rails, bridges, road furniture and the road coats reaching the tile, also
     * where only a neighbour's join changes them. CPU only.
     */
    private static RouteChange compare(BoardScene before, BoardScene after, List<Coords> candidates, TerrainLod lod,
          TerrainSettings settings, boolean columns) {
        var routed = candidates.stream().filter(at -> routed(before, at) || routed(after, at)).toList();
        var was = RouteSide.of(before, routed, lod, settings, columns);
        var shown = RouteSide.of(after, routed, lod, settings, columns);
        return new RouteChange(lod, was, shown,
              settings.call(() -> routed.stream().filter(at -> !sameRoutes(was, shown, at)).toList()));
    }

    /** {@link #compare}'s changed tiles. */
    static List<Coords> changedRoutes(BoardScene before, BoardScene after, List<Coords> candidates, TerrainLod lod,
          TerrainSettings settings, boolean columns) {
        return compare(before, after, candidates, lod, settings, columns).changed();
    }

    /** Whether the committed builders' inputs and results for {@code at}'s route pieces agree on both sides. */
    private static boolean sameRoutes(RouteSide was, RouteSide shown, Coords at) {
        var before = was.scene().tile(at);
        var after = shown.scene().tile(at);
        var oldFeatures = BoardFeatures.fittedControls(was.scene(), before).stream().filter(f -> routeFeature(before, f)).toList();
        var features = BoardFeatures.fittedControls(shown.scene(), after).stream().filter(f -> routeFeature(after, f)).toList();
        var oldCoats = coats(was, at);
        var coats = coats(shown, at);
        if (!oldFeatures.equals(features) || !features.isEmpty() && before.elevation() != after.elevation()
              || !Objects.equals(was.infrastructure().bridges().get(at), shown.infrastructure().bridges().get(at))
              || oldCoats.size() != coats.size()
              || IntStream.range(0, coats.size()).anyMatch(i -> !sameCoat(at, oldCoats.get(i), coats.get(i)))
              || !sameBridge(was.infrastructure().bridgeShapes().get(at), shown.infrastructure().bridgeShapes().get(at))) {
            return false;
        }
        if (features.isEmpty()) { return true; }
        for (var feature : features) {
            if (BoardRail.route(feature) && !BoardRail.layout(was.scene(), before, feature, was.surfaces().get(at),
                  was.infrastructure().bridgeShapes()::get).equals(BoardRail.layout(shown.scene(), after, feature,
                  shown.surfaces().get(at), shown.infrastructure().bridgeShapes()::get))) { return false; }
            if (BoardWall.building(after.cover(), feature) && BoardWall.supports(feature.asset())
                  && !BoardWall.layout(was.scene(), before, feature).equals(BoardWall.layout(shown.scene(), after, feature))) {
                return false;
            }
        }
        return was.surfaces().get(at).groundFaces().equals(shown.surfaces().get(at).groundFaces());
    }

    /**
     * The CPU half of the editor preview's pending infrastructure, on a worker: of {@code candidates}, the tiles whose
     * route pieces differ between {@code before}, the installed scene, and {@code scene}, the pending one, at each
     * one's terrain level ({@link #compare}). {@link #previewRoutes} installs the result. On the render thread.
     */
    CompletableFuture<List<RouteChange>> compareRoutes(BoardScene before, BoardScene scene, Set<Coords> candidates) {
        Map<TerrainLod, List<Coords>> groups = new EnumMap<>(TerrainLod.class);
        for (Coords at : candidates) {
            if (editorTile(at) == null) { continue; }
            var lod = chunks.get(at.getX() / CHUNK_SIZE * chunkRows + at.getY() / CHUNK_SIZE).lod;
            groups.computeIfAbsent(lod, ignored -> new ArrayList<>()).add(at);
        }
        TerrainSettings settings = installedSettings;
        boolean columns = columns();
        return CompletableFuture.supplyAsync(() -> groups.entrySet().stream()
              .map(group -> compare(before, scene, group.getValue(), group.getKey(), settings, columns)).toList());
    }

    /**
     * The editor preview's pending infrastructure ({@link EditorRoutes}) of {@code changes} ({@link #compareRoutes}): the
     * changed tiles replaced by their pieces in the pending scene, or where they keep none, by their installed pieces
     * that go. Installed until the returned preview is disposed. On the render thread.
     */
    EditorRoutes previewRoutes(List<RouteChange> changes) {
        EditorRoutes result = new EditorRoutes();
        try {
            installedSettings.run(() -> {
                for (var change : changes) {
                    var changed = change.changed();
                    if (changed.isEmpty()) { continue; }
                    result.coords.addAll(changed);
                    var shown = pieces(result, change.shown(), changed, change.lod());
                    var gone = changed.stream().filter(at -> shown.get(at).isEmpty()).toList();
                    var removed = gone.isEmpty() ? Map.<Coords, List<ModelInstance>>of()
                          : pieces(result, change.was(), gone, change.lod());
                    for (Coords at : changed) {
                        boolean goes = shown.get(at).isEmpty();
                        var ghosts = goes ? removed.get(at) : shown.get(at);
                        if (ghosts.isEmpty()) { continue; }
                        if (goes) { result.removed.add(at); }
                        result.ghosts.put(at, List.copyOf(ghosts));
                    }
                }
            });
            editorRoutes = result;
            refreshShadows();
            return result;
        } catch (RuntimeException | Error failure) {
            result.dispose();
            throw failure;
        }
    }

    /** {@code side}'s route pieces of {@code coords}, built as a chunk at {@code lod} builds them, owned by {@code owner}. */
    private Map<Coords, List<ModelInstance>> pieces(EditorRoutes owner, RouteSide side, List<Coords> coords, TerrainLod lod) {
        var infrastructure = side.infrastructure();
        var prepared = new Prepared(side.surfaces(), null, null, Map.of(), Map.of(), Map.of(), Map.of(),
              infrastructure.roads(), infrastructure.bridges(), infrastructure.bridgeShapes(), Map.of(), Set.of(), Map.of(), false);
        var build = new ChunkBuild(side.scene(), 0, 0, floor, lod, columns(), false, prepared, null,
              new TerrainLoadProgress());
        build.preview = owner;
        owner.carriers.add(build.chunk);
        Map<Coords, List<ModelInstance>> result = new HashMap<>();
        for (Coords at : coords) {
            var tile = side.scene().tile(at);
            build.chunk.tileMeshes.put(at, new TileMesh());
            int first = build.chunk.props.size();
            collectFeatures(build, tile, side.surfaces().get(at), ground(build, at));
            List<ModelInstance> ghosts = new ArrayList<>();
            build.chunk.props.subList(first, build.chunk.props.size()).forEach(prop -> ghosts.add(prop.instance));
            build.layers.get(0).finish(ghosts);
            build.layers.get(0).geometry.clear();
            // Only these emitted bridge meshes are owned; feature models stay in the asset library.
            ghosts.stream().skip(build.chunk.props.size() - first).forEach(ghost -> owner.owned.add(ghost.model));
            previewRoads(build, tile, ghosts);
            result.put(at, ghosts);
        }
        return result;
    }

    /** The committed road triangles and coverage masks, with ordinary UVs for the shared ghost shader. */
    private void previewRoads(ChunkBuild build, BoardScene.Tile tile, List<ModelInstance> ghosts) {
        var layer = new GpuTerrainLayer();
        for (RoadPatch patch : build.prepared.roads().getOrDefault(tile.coords(), List.of())) {
            if (!routeCoat(patch)) { continue; }
            Texture mask = build.preview.mask(patch.mask().pixels());
            Material material = new Material(TextureAttribute.createDiffuse(mask), FloatAttribute.createAlphaTest(.01f),
                  IntAttribute.createCullFace(GL20.GL_BACK));
            layer.addTriangles(material, meshes -> {
                for (var triangle : patch.triangles()) {
                    Vector3 normal = new Vector3(triangle.b()).sub(triangle.a()).crs(new Vector3(triangle.c()).sub(triangle.a())).nor();
                    var points = new MeshPartBuilder.VertexInfo[3];
                    int index = 0;
                    for (Vector3 p : List.of(triangle.a(), triangle.b(), triangle.c())) {
                        points[index++] = vertex(p, normal, GpuRoads.maskU(tile.coords(), patch.mask(), p),
                              GpuRoads.maskV(tile.coords(), patch.mask(), p), Color.WHITE);
                    }
                    meshes.get().triangle(points[0], points[1], points[2]);
                }
            });
        }
        List<ModelInstance> roads = new ArrayList<>();
        layer.finish(roads);
        roads.forEach(road -> build.preview.owned.add(road.model));
        ghosts.addAll(roads);
    }

    private boolean replaced(Prop prop) {
        return editorRoutes != null && prop.infrastructure && editorRoutes.coords.contains(prop.coords());
    }

    private static void renderRetained(Array<Renderable> parts, ModelBatch target, Environment lighting) {
        for (Renderable part : parts) {
            part.shader = null; part.environment = lighting; target.render(part);
        }
    }

    /** A previewed chunk draws its props one by one, so the replaced pieces never show under their ghosts. */
    private void renderRetainedProps(Chunk chunk, ModelBatch target, Environment lighting, boolean shadows) {
        for (Prop prop : chunk.cutaways) {
            if (!drawn(prop, shadows) || !Float.isNaN(prop.floorZ) && !chunk.interiorFloorVisible(prop)) { continue; }
            if (shadows || !chunk.faded.contains(prop)) { target.render(prop.instance(), lighting); }
            else if (prop.hoverOpaque != null) { target.render(prop.hoverOpaque, lighting); }
        }
        if (!shadows && !fluffHidden) {
            for (InteriorStruts strut : chunk.struts) {
                if (chunk.interiorVisible(strut.coords)) { target.render(strut.instance, lighting); }
            }
        }
    }

    /**
     * The editor preview's ghost of the decal {@code feature} of {@code owner}: its paint on the drawn receivers of every
     * hex it covers, by a chunk's own paint helpers ({@link #paintFaces}, {@link #paintTriangles}), in {@code material}
     * with the paint's image. Null without its image or anything to paint; the model is the caller's.
     */
    Model decalGhost(Coords owner, BoardScene.Feature feature, Material material) {
        var object = feature.decoration();
        Texture texture = assets.decorationPaint(object.asset());
        if (texture == null || coverScene == null) { return null; }
        material.set(TextureAttribute.createDiffuse(texture));
        return installedSettings.call(() -> {
            var builder = new com.badlogic.gdx.graphics.g3d.utils.ModelBuilder();
            builder.begin();
            MeshPartBuilder paint = builder.part("paint", GL20.GL_TRIANGLES, VertexAttributes.Usage.Position
                  | VertexAttributes.Usage.Normal | VertexAttributes.Usage.ColorPacked | VertexAttributes.Usage.TextureCoordinates,
                  material);
            int triangles = 0;
            for (Coords recipient : BoardDecals.footprint(owner, object, coverScene.width(), coverScene.height())) {
                TileMesh mesh = editorTile(recipient);
                if (mesh == null) { continue; }
                Chunk chunk = chunks.get(recipient.getX() / CHUNK_SIZE * chunkRows + recipient.getY() / CHUNK_SIZE);
                var ground = tacticalSurface(coverScene, chunk, recipient, floor).faces();
                var faces = paintFaces(owner, object, recipient, ground, mesh.bridgeShape, mesh.props);
                paintTriangles(() -> paint, owner, object, faces);
                triangles += faces.size();
            }
            Model model = builder.end();
            if (triangles == 0) { model.dispose(); return null; }
            return model;
        });
    }

    /**
     * The placements of the objects on the tiles that differ, by identity, between the tiles a chunk last showed and
     * the next ones: the new placement, or null for an object that left them. The map source keeps unchanged tile
     * objects, so an edit reads only the tiles it changed. Without earlier tiles every tile counts as changed.
     */
    private static Map<String, EditorPlacement> changedPlacements(List<BoardScene.Tile> shown, List<BoardScene.Tile> next) {
        boolean all = shown == null || shown.size() != next.size();
        Map<String, EditorPlacement> result = new HashMap<>();
        for (int index = 0; index < next.size(); index++) {
            var tile = next.get(index);
            if (all || shown.get(index) != tile) {
                if (!all) {
                    for (var feature : shown.get(index).features()) {
                        if (feature.decoration() != null) { result.putIfAbsent(feature.decoration().id(), null); }
                    }
                }
                for (var feature : tile.features()) {
                    if (feature.decoration() != null) {
                        result.put(feature.decoration().id(), new EditorPlacement(tile.coords(), feature));
                    }
                }
            }
        }
        return result;
    }

    /** Derived from installed geometry. These are display data, never document state. */
    List<EditorObject> editorObjects(Coords coords) {
        TileMesh tile = editorTile(coords);
        if (tile == null) { return List.of(); }
        return installedSettings.call(() -> {
            List<EditorObject> result = new ArrayList<>(editorProps(coords).stream().filter(prop -> prop.decorationId != null)
                  .map(prop -> new EditorObject(prop.decorationId, new BoundingBox(prop.bounds()), prop.anchorZ() / BoardGeometry.level(),
                        new Vector3(prop.instance().transform.val[Matrix4.M03], prop.instance().transform.val[Matrix4.M13], prop.anchorZ()),
                        (prop.previewFeature == null ? prop.decorationFeature : prop.previewFeature).decoration().placement(),
                        drawn(prop, false), false))
                  .toList());
            result.addAll(editorDecals(coords, tile));
            return result;
        });
    }

    /**
     * The decals {@code coords} owns as drawn: the box of their paint over every hex it covers (a flat footprint on
     * its support), anchored on that support under their anchor. A terrain's own paint (an elevator's plate) is no
     * object, as {@link #decorationHit} has it. The caller holds the installed settings.
     */
    private List<EditorObject> editorDecals(Coords coords, TileMesh tile) {
        List<EditorObject> result = new ArrayList<>();
        if (coverScene == null) { return result; }
        for (PaintedDecal own : tile.paint) {
            var object = own.stamp().object();
            if (!own.stamp().owner().equals(coords) || BoardFeatures.terrainOwned(object)) { continue; }
            BoundingBox box = new BoundingBox().inf();
            float x = BoardGeometry.centerX(coords) + (float) object.x() * BoardGeometry.width();
            float y = BoardGeometry.centerY(coords) + (float) object.y() * BoardGeometry.height();
            float z = Float.NEGATIVE_INFINITY;
            for (Coords recipient : BoardDecals.footprint(coords, object, coverScene.width(), coverScene.height())) {
                TileMesh mesh = editorTile(recipient);
                if (mesh == null) { continue; }
                for (PaintedDecal paint : mesh.paint) {
                    if (!paint.stamp().owner().equals(coords) || !paint.stamp().object().id().equals(object.id())) { continue; }
                    for (var face : paint.faces()) {
                        for (Vector3 corner : List.of(face.a(), face.b(), face.c())) { box.ext(corner.x, corner.y, corner.z + paint.lift()); }
                        z = Math.max(z, face.height(x, y));
                    }
                }
            }
            if (box.min.x > box.max.x) { continue; }
            if (!Float.isFinite(z)) { z = box.min.z; }
            result.add(new EditorObject(object.id(), box, z / BoardGeometry.level(), new Vector3(x, y, z), object.placement(),
                  true, true));
        }
        return result;
    }

    /**
     * The cut through the actual ground triangles along the horizontal unit axis ({@code axisX}, {@code axisY}) through
     * the hex centre; internal hexes have no exposed vertical wall of their own. With {@code solid}, a see-through
     * liquid's surface gives way to its bed (molten lava stays opaque).
     */
    List<Vector3> editorGroundProfile(Coords coords, float axisX, float axisY, boolean solid) {
        if (editorTile(coords) == null || coverScene == null) { return List.of(); }
        return installedSettings.call(() -> {
            Chunk chunk = chunks.get(coords.getX() / CHUNK_SIZE * chunkRows + coords.getY() / CHUNK_SIZE);
            var surface = tacticalSurface(coverScene, chunk, coords, floor);
            List<BoardSurface.Face> faces = new ArrayList<>(surface.slopes());
            if (solid && !coverScene.tile(coords).liquid().molten()) {
                Set<BoardSurface.Face> liquid = Collections.newSetFromMap(new IdentityHashMap<>());
                liquid.addAll(surface.water());
                surface.top().stream().filter(face -> !liquid.contains(face)).forEach(faces::add);
                surface.faces().stream().filter(face -> face.finish() == BoardSurface.Finish.BED).forEach(faces::add);
            } else {
                faces.addAll(surface.top());
            }
            List<Vector3> samples = new ArrayList<>();
            for (int i = 0; i <= 48; i++) {
                float position = (i / 48f - .5f) * .998f * BoardGeometry.width();
                float x = BoardGeometry.centerX(coords) + position * axisX;
                float y = BoardGeometry.centerY(coords) + position * axisY;
                float z = Float.NEGATIVE_INFINITY;
                for (var face : faces) { z = Math.max(z, face.height(x, y)); }
                if (Float.isFinite(z)) { samples.add(new Vector3(x, y, z)); }
            }
            return samples;
        });
    }

    /** A second camera borrows the installed hex meshes, transforms, materials and animation clocks. */
    void renderEditorSection(Camera camera, Coords coords, String isolate) {
        TileMesh tile = editorTile(coords);
        if (tile == null) { return; }
        installedSettings.call(() -> {
            shadingPass++;
            // This camera also renders stand-alone palette samples, before any main-board pass. Classic's ice reads the
            // per-hex texture too.
            biomes.update(coverScene);
            batch.begin(camera);
            try {
                batch.render((out, pool) -> {
                    for (TileRange range : tile.ranges) {
                        if (range.layer() == 5 || !isolate.isEmpty()) { continue; }
                        Renderable part = pool.obtain();
                        part.worldTransform.idt(); part.material = range.material();
                        part.meshPart.set("editor-section", range.mesh(), range.offset(), range.count(), GL20.GL_TRIANGLES);
                        if (part.material.has(Sculpt.TYPE) || part.material.has(GpuIceShader.TYPE)) {
                            prepareTerrainMaterial(part.material, range.mesh().getVertexAttributes());
                        }
                        out.add(part);
                    }
                }, environment);
                for (Prop prop : editorProps(coords)) {
                    if (!Float.isNaN(prop.floorZ) || !isolate.isEmpty() && !isolate.equals(prop.decorationId)) { continue; }
                    // A close-up: a building or object with mesh levels draws its near one, whatever the board's zoom.
                    batch.render(prop.near(), environment);
                }
                if (isolate.isEmpty()) { for (ModelInstance pane : tile.glass) { batch.render(pane, environment); } }
            } finally { batch.end(); }
            return null;
        });
    }

    record EditorSectionHit(String object, String component) { }

    /** Side-view picking borrows the same installed model, bridge and ground triangles as board picking. */
    EditorSectionHit editorSectionHit(Coords coords, Ray ray) {
        return installedSettings.call(() -> editorSectionHitInstalled(coords, ray));
    }

    String editorSectionPick(Coords coords, Ray ray) {
        EditorSectionHit hit = editorSectionHit(coords, ray);
        return hit == null || hit.object().isEmpty() ? null : hit.object();
    }

    private EditorSectionHit editorSectionHitInstalled(Coords coords, Ray ray) {
        TileMesh tile = editorTile(coords);
        if (tile == null) { return null; }
        Vector3 point = new Vector3(); float nearest = Float.POSITIVE_INFINITY; EditorSectionHit result = null;
        if (tile.bridgeShape != null) {
            nearest = tile.bridgeShape.hit(ray);
            if (Float.isFinite(nearest)) { result = new EditorSectionHit("", "bridge"); }
        }
        for (Prop prop : editorProps(coords)) {
            if (!Float.isNaN(prop.floorZ)) { continue; }
            String component = prop.industrial ? "industry" : "building".equals(prop.receiver) ? "building"
                  : "bridge".equals(prop.receiver) ? "bridge" : prop.tree() ? "vegetation" : prop.structure ? "fuelTank" : "";
            if ((prop.decorationId != null || !component.isEmpty())
                  && hitProp(prop, ray, point) && ray.origin.dst2(point) < nearest) {
                nearest = ray.origin.dst2(point);
                result = new EditorSectionHit(prop.decorationId == null ? "" : prop.decorationId, component);
            }
        }
        if (coverScene != null) {
            var ground = BoardGeometry.hit(coverScene, ray, List.of(coverScene.tile(coords)), floor, this::tacticalSurface);
            if (ground != null && ground.distance() < nearest) { result = new EditorSectionHit("", "ground"); }
        }
        return result;
    }

    /**
     * Native editor selection uses the same installed instances and triangle picker as both board cameras. Water is
     * see-through: an object or a decal beneath its surface stays pickable, down to its bed.
     */
    DecorationHit decorationHit(BoardScene scene, Ray ray) {
        if (!ready(scene)) { return null; }
        return installedSettings.call(() -> {
            BoardGeometry.Hit solid = hitInstalled(presentation(scene), ray, true, false, false, Float.POSITIVE_INFINITY, true);
            float nearest = solid == null ? Float.POSITIVE_INFINITY : solid.distance() + .001f;
            DecorationHit result = null;
            Vector3 point = new Vector3();
            for (Chunk chunk : chunks) {
                if (!Intersector.intersectRayBoundsFast(ray, chunk.bounds)) { continue; }
                for (Prop prop : chunk.props) {
                    // What this view draws: by the stand or as object parts; the Tactical View draws no placed plant.
                    if (prop.decorationId != null && drawn(prop, false) && hitProp(prop, ray, point)
                          && ray.origin.dst2(point) <= nearest) {
                        nearest = ray.origin.dst2(point);
                        result = new DecorationHit(prop.coords(), prop.decorationId, nearest, prop.instance().transform.val[Matrix4.M23]);
                    }
                }
                for (TileMesh tile : chunk.tileMeshes.values()) {
                    for (PaintedDecal paint : tile.paint) {
                        // A terrain's own paint (an elevator's plate) is no object: the hex beneath is picked.
                        if (BoardFeatures.terrainOwned(paint.stamp().object())) { continue; }
                        for (var face : paint.faces()) {
                            if (Intersector.intersectRayTriangle(ray, new Vector3(face.a()).add(0, 0, paint.lift()),
                                  new Vector3(face.b()).add(0, 0, paint.lift()), new Vector3(face.c()).add(0, 0, paint.lift()), point)
                                  && ray.origin.dst2(point) <= nearest) {
                                nearest = ray.origin.dst2(point);
                                result = new DecorationHit(paint.stamp().owner(), paint.stamp().object().id(), nearest, point.z);
                            }
                        }
                    }
                }
            }
            return result;
        });
    }

    /** Hover and clicks use the ground beneath foliage and industrial cover, which have no occupiable floors. */
    BoardGeometry.Hit selectionHit(BoardScene scene, Ray ray) {
        return hit(scene, ray, false);
    }

    /** Placement uses the same triangles, restricted to ground and authored supporting structures. */
    BoardGeometry.Hit placementHit(BoardScene scene, Ray ray) {
        if (!ready(scene)) { return null; }
        return installedSettings.call(() -> hitInstalled(presentation(scene), ray, true, true, false));
    }

    /**
     * The levels a unit can stand on, the one rule for the hover outline in the game and the editor: the ground or a
     * water surface, a bridge deck, or a building's storey or roof. Placed objects, trees, industrial cover and fuel
     * tanks are looked through, whatever their height.
     */
    BoardGeometry.Hit walkableHit(BoardScene scene, Ray ray) {
        if (!ready(scene)) { return null; }
        return installedSettings.call(() -> hitInstalled(presentation(scene), ray, false, false, true));
    }

    /** Physical hits for attack effects still include foliage. */
    BoardGeometry.Hit hit(BoardScene scene, Ray ray) {
        return hit(scene, ray, true);
    }

    /** A flight segment cannot reach chunks beyond its endpoint. */
    BoardGeometry.Hit hit(BoardScene scene, Ray ray, float length) {
        if (!ready(scene)) { return null; }
        return installedSettings.call(() -> hitInstalled(presentation(scene), ray, true, false, false, length));
    }

    private BoardGeometry.Hit hit(BoardScene scene, Ray ray, boolean includeFoliage) {
        if (!ready(scene)) { return null; }
        return installedSettings.call(() -> hitInstalled(presentation(scene), ray, includeFoliage));
    }

    private BoardGeometry.Hit hitInstalled(BoardScene scene, Ray ray, boolean includeFoliage) {
        return hitInstalled(scene, ray, includeFoliage, false, false);
    }

    private BoardGeometry.Hit hitInstalled(BoardScene scene, Ray ray, boolean includeFoliage, boolean placement, boolean walkable) {
        return hitInstalled(scene, ray, includeFoliage, placement, walkable, Float.POSITIVE_INFINITY);
    }

    private BoardGeometry.Hit hitInstalled(BoardScene scene, Ray ray, boolean includeFoliage, boolean placement, boolean walkable,
          float length) {
        return hitInstalled(scene, ray, includeFoliage, placement, walkable, length, false);
    }

    /** With {@code throughWater} a see-through liquid's surface gives way to its bed ({@link BoardGeometry#hit}). */
    private BoardGeometry.Hit hitInstalled(BoardScene scene, Ray ray, boolean includeFoliage, boolean placement, boolean walkable,
          float length, boolean throughWater) {
        // The caller has selected the installed coordinate system. A source snapshot that update has not yet
        // received still uses the authoritative-snapshot fallback, as before.
        boolean finished = tiles == scene.tiles();
        Coords result = null;
        float nearest = length * length;
        boolean hardSurface = false;
        String receiver = "ground";
        List<BoardScene.Tile> candidates = new ArrayList<>();
        Vector3 hit = new Vector3();
        for (int index = 0; index < chunks.size(); index++) {
            Chunk chunk = chunks.get(index);
            if (!Intersector.intersectRayBoundsFast(ray, chunk.bounds)) {
                continue;
            }
            if (Float.isFinite(length) && Intersector.intersectRayBounds(ray, chunk.bounds, hit)
                  && ray.origin.dst2(hit) > nearest) { continue; }
            // Reuse render bounds before invoking the shared surface picker; a pointer event must not
            // inspect six neighbors and allocate geometry bounds for every hex of a 40,000-hex board.
            if (finished) { chunkTiles(scene, index, candidates); }
            // Each graphics mode picks the installed bridge facets that its opaque layer draws.
            for (var entry : chunk.tileMeshes.entrySet()) {
                var bridge = entry.getValue().bridgeShape;
                if (bridge == null) { continue; }
                // No one stands on a pier: the hover outline looks past it to the deck or the ground.
                float distance = bridge.hit(ray, !walkable);
                if (distance < nearest) {
                    nearest = distance;
                    ray.getEndPoint(hit, (float) Math.sqrt(distance));
                    var footprint = BoardGeometry.tile(scene, hit.x, hit.y);
                    result = footprint == null ? entry.getKey() : footprint.coords();
                    hardSurface = false;
                    receiver = "bridge";
                }
            }
            for (Prop prop : chunk.props) {
                if (placement && prop.receiver == null || walkable && !"building".equals(prop.receiver)
                      && !"bridge".equals(prop.receiver) && Float.isNaN(prop.floorZ)) { continue; }
                // What the zoom hides is looked through. What the Tactical View hides is still picked (shown).
                if ((prop.tree() || prop.industrial) && !includeFoliage || !shown(prop, false)) { continue; }
                if (hitProp(prop, ray, hit)) {
                    float distance = ray.origin.dst2(hit);
                    if (distance < nearest) {
                        nearest = distance;
                        result = prop.coords();
                        hardSurface = prop.hardSurface;
                        receiver = prop.receiver == null ? "ground" : prop.receiver;
                    }
                }
            }
        }
        pickingSurfaces.columns(columns());
        BoardGeometry.Hit groundHit = finished
              ? BoardGeometry.hit(scene, ray, candidates, floor, null, this::tacticalSurface, throughWater)
              : BoardGeometry.hit(scene, ray, scene.tiles(), BoardGeometry.floor(scene), pickingSurfaces, null, throughWater);
        return groundHit != null && groundHit.distance() <= nearest ? groundHit
              : result == null ? null : new BoardGeometry.Hit(result, nearest, hardSurface, receiver);
    }

    /** Shared by scenery placement and picking; zoom/cutaways do not change the supporting solid. */
    private boolean hitProp(Prop prop, Ray ray, Vector3 hit) {
        if (!Intersector.intersectRayBoundsFast(ray, prop.bounds())) { return false; }
        Matrix4 inverse = new Matrix4(prop.instance().transform).inv();
        // A ray down the very seam where pieces meet (a building hex's centre, where its roof pieces' corners lie) can
        // slip between their triangles: a hair aside, it lands.
        float aside = .3f * BoardGeometry.hexScale();
        for (float[] offset : SEAM_ASIDE) {
            // Transform the direction directly: Ray.mul subtracts distant points and loses picking precision.
            Ray local = new Ray(new Vector3(ray.origin).add(offset[0] * aside, offset[1] * aside, 0).mul(inverse),
                  new Vector3(ray.direction).rot(inverse));
            boolean found = prop.building != null ? prop.building.hit(local, hit)
                  : Intersector.intersectRayTriangles(local, prop.pickingTriangles != null ? prop.pickingTriangles
                        : featureTriangles.computeIfAbsent(prop.pickingModel, GpuTerrain::triangles), hit);
            if (found) {
                hit.mul(prop.instance().transform);
                return true;
            }
        }
        return false;
    }

    /** Where {@link #hitProp} casts again, in turn, when its ray finds nothing: the ray itself first. */
    private static final float[][] SEAM_ASIDE = { { 0, 0 }, { .6f, .8f }, { -.8f, .6f }, { -.6f, -.8f }, { .8f, -.6f } };

    static List<Vector3> triangles(Model model) { return triangles(model.nodes, part -> true); }

    static List<Vector3> triangles(Node node) { return triangles(List.of(node), part -> true); }

    /** The triangles of {@code nodes}' enabled parts that {@code parts} accepts, in model space. */
    private static List<Vector3> triangles(Iterable<Node> nodes, java.util.function.Predicate<NodePart> parts) {
        List<Vector3> result = new ArrayList<>();
        Map<Mesh, float[]> vertices = new HashMap<>();
        Map<Mesh, short[]> indices = new HashMap<>();
        for (Node node : nodes) { triangles(node, parts, result, vertices, indices); }
        return result;
    }

    private static void triangles(Node node, java.util.function.Predicate<NodePart> parts, List<Vector3> result,
          Map<Mesh, float[]> vertexCache, Map<Mesh, short[]> indexCache) {
        for (var part : node.parts) {
            if (!part.enabled || part.meshPart.primitiveType != GL20.GL_TRIANGLES || !parts.test(part)) { continue; }
            Mesh mesh = part.meshPart.mesh;
            int stride = mesh.getVertexSize() / Float.BYTES;
            int offset = mesh.getVertexAttribute(VertexAttributes.Usage.Position).offset / Float.BYTES;
            float[] vertices = vertexCache.computeIfAbsent(mesh,
                  key -> key.getVertices(new float[key.getNumVertices() * stride]));
            short[] indices = indexCache.computeIfAbsent(mesh, key -> {
                short[] values = new short[key.getNumIndices()];
                key.getIndices(values);
                return values;
            });
            Vector3[] corners = new Vector3[3];
            for (int index = part.meshPart.offset; index + 2 < part.meshPart.offset + part.meshPart.size; index += 3) {
                for (int k = 0; k < 3; k++) {
                    int at = (indices.length == 0 ? index + k : Short.toUnsignedInt(indices[index + k])) * stride + offset;
                    corners[k] = new Vector3(vertices[at], vertices[at + 1], vertices[at + 2]).mul(node.globalTransform);
                }
                // A zero-area triangle (a repeated corner) has no plane: Intersector reads it as holding every point and
                // returns the ray's origin, which would beat every real hit.
                if (new Vector3(corners[1]).sub(corners[0]).crs(new Vector3(corners[2]).sub(corners[0])).len2() < 1e-6f) {
                    continue;
                }
                result.add(corners[0]);
                result.add(corners[1]);
                result.add(corners[2]);
            }
        }
        for (Node child : node.getChildren()) { triangles(child, parts, result, vertexCache, indexCache); }
    }

    /**
     * The 3D Graphics: with {@code columns} (Classic) every later build lays each hex as its tileset
     * column ({@link GpuTilesetTerrain}) and keeps only its structures, routes and placed objects: no relief, water or
     * lava field, plant, road or biome. The next {@link #update} after a change rebuilds the whole board, and until it
     * commits {@link #ready} is false, so the view shows its loading stage either way.
     */
    void setColumns(boolean columns) { this.columns = columns; }

    void setTessellation(boolean tessellation) { this.tessellation = tessellation; }

    /** The battle view's scene for the shadow map's size (GpuTerrainShadows.setScene). */
    void setShadowScene(float pixels, boolean high) { shadowMap.setScene(pixels, high); }

    /** The switch, which the installed chunks follow once the terrain is ready (unlike {@link #columns()}). */
    boolean tessellation() { return tessellation; }

    /** Whether the installed chunks are the Classic columns. */
    boolean columns() { return installedColumns; }

    /**
     * The Tactical View's draw rule, with no rebuild: scatter trees, placed plants, grass and biome vegetation,
     * building, industrial, fuel tank and placed object models, decals and paint, scars and ground damage, water and its
     * wave simulations, spores and geysers hide; the terrain, roads, bridges, rough, rubble, fortifications, tunnel
     * portals, routes and a woods cover's look (its trees and its skin, Prop#cover, BoardFeatures#coverSkin) stay. Picking still sees what it hides, so a click picks the same hex and floor as before.
     * The 3D prop caches and their pages stay as they are: the passes draw each chunk's kept props instead
     * (Chunk#keptProps), so only the shadow map is redrawn.
     */
    void setFluffHidden(boolean hidden) {
        if (fluffHidden == hidden) { return; }
        fluffHidden = hidden;
        shadowMap.invalidate();
    }

    boolean fluffHidden() { return fluffHidden; }

    /** Native checks: the twin draws of the last depth pre-pass. */
    int prepassDraws() { return prepassDraws; }

    /**
     * Native checks: whether a prop, terrain or top page the last frame showed has a merge still to make. A merge changes
     * the draw order, so where surfaces tie in depth, a few pixels change when it lands. Scar and paint pages are left
     * out: they advance after the opaque flush, past the frame's page deadline whenever that flush outlasts it.
     */
    boolean pagesMerging() {
        return propBatch.merging() || terrainPages.merging() || topPages.merging();
    }

    /** In render's colour stage: whether a depth pre-pass drew, so the scene depth holds its surfaces. */
    boolean prepassed() { return prepassed; }

    /** The opaque batch's render context, for a pass in render's colour stage (GpuAmbientOcclusion). */
    RenderContext renderContext() { return batch.getRenderContext(); }

    /** Native checks: each generic colour program with a depth twin, and the twin. */
    Map<Shader, Shader> prepassTwins() { return Collections.unmodifiableMap(prepassTwins); }

    private void recacheProps(Chunk chunk) {
        scarSurfaceRevision++;
        GpuPropBatch.disposeMeshes(chunk.shadowPropRenderables);
        chunk.shadowPropRenderables.clear();
        chunk.disposeKeptProps();
        chunk.cacheProps();
    }

    /** Packs the kept props of the chunks the Tactical View draws for the first time, outside any batch. */
    private void packKeptProps() {
        if (fluffHidden) { chunks.forEach(Chunk::keptProps); }
    }

    /**
     * The ground material cache's fill of one page (GpuMaterialCache.Tops): the cache tops that reach the rectangle
     * (world units), and the charted walls whose charts do, in their generic materials, drawn from {@code camera} with
     * the fill programs, unlit.
     */
    private void drawCacheTops(Camera camera, float minX, float minY, float maxX, float maxY) {
        fillShaders();
        shadingPass++;
        fillBatch.begin(camera);
        try {
            for (Chunk chunk : chunks) {
                boolean tops = reaches(chunk.topBounds, minX, minY, maxX, maxY);
                boolean walls = reaches(chunk.wallBounds, minX, minY, maxX, maxY);
                if (!tops && !walls) { continue; }
                for (Renderable part : chunk.topRenderables) {
                    if (part.meshPart.size == 0 || !(GpuMaterialCache.isWall(part.material) ? walls : tops)) {
                        continue;
                    }
                    Renderable copy = fillCopies.of(part, materialCache.fillMaterial(part.material));
                    copy.shader = null;
                    copy.environment = null;
                    fillBatch.render(copy);
                }
            }
        } finally {
            fillBatch.end();
            fillCopies.clear();
        }
    }

    /** {@code into} extended by {@code box} where that holds anything: two empty boxes would extend to every point. */
    private static BoundingBox union(BoundingBox into, BoundingBox box) { return box.isValid() ? into.ext(box) : into; }

    /** Whether {@code bounds} (a valid box) reaches the XY rectangle. */
    private static boolean reaches(BoundingBox bounds, float minX, float minY, float maxX, float maxY) {
        return bounds.isValid() && bounds.max.x >= minX && bounds.min.x <= maxX && bounds.max.y >= minY
              && bounds.min.y <= maxY;
    }

    /**
     * Whether the ground material cache draws this terrain's level tops: the view's own terrain (prints and previews
     * borrow its assets and draw directly), with every sculpted surface a plain dielectric. The cache keeps no emission
     * (a map or a constant glow), metallic or scattering, map or factor (their means are zero only where every texel
     * is).
     */
    private boolean cachesGround() {
        if (!GpuMaterialCache.ENABLED || borrowedAssets) { return false; }
        var finish = assets.finishArrays(FINISHES, SCULPT_LAYERS.size());
        for (int material = 0; material < SCULPT_LAYERS.size(); material++) {
            if (finish.tint()[material * 4 + 3] >= 0 || finish.glow()[material * 3] != 0
                  || finish.glow()[material * 3 + 1] != 0 || finish.glow()[material * 3 + 2] != 0
                  || finish.surface()[material * 4 + 1] != 0 || finish.surface()[material * 4 + 2] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Each chunk's cache tops and charted walls for this frame of the ground material cache, with whether
     * {@code camera} sees them.
     */
    private void cacheChunks(Camera camera) {
        for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
            Chunk chunk = chunks.get(chunkIndex);
            if (chunk.topRenderables.isEmpty()) { continue; }
            materialCache.chunk(terrainPage(chunkIndex), chunk.topBounds,
                  camera.frustum.boundsInFrustum(chunk.topBounds), chunk.cacheSince);
            if (chunk.wallBounds.isValid()) {
                materialCache.walls(terrainPage(chunkIndex), chunk.charts,
                      camera.frustum.boundsInFrustum(chunk.wallWorld), chunk.cacheSince);
            }
        }
    }

    /** The terrain page of a chunk: a fixed square of {@link GpuTerrainPages#CHUNKS_PER_PAGE} chunks a side. */
    private int terrainPage(int chunkIndex) {
        int size = GpuTerrainPages.CHUNKS_PER_PAGE, rows = (chunkRows + size - 1) / size;
        return chunkIndex / chunkRows / size * rows + chunkIndex % chunkRows / size;
    }

    /** The prop page of a chunk: a fixed square of {@link GpuPropBatch#CHUNKS_PER_PAGE} chunks a side. */
    private int propPage(int chunkIndex) {
        int size = GpuPropBatch.CHUNKS_PER_PAGE, rows = (chunkRows + size - 1) / size;
        return chunkIndex / chunkRows / size * rows + chunkIndex % chunkRows / size;
    }

    /** The opaque world, for another camera than the view's (a print): its tops draw directly. */
    void render(Camera camera) {
        render(camera, null, null, false);
    }

    /**
     * The view's opaque world, with a benchmark's stage boundaries: {@code prepassStage} runs after the frame's
     * preparation and gather, {@code colourStage} after the depth pre-pass, before the colour draws. The view's camera
     * drives the ground material cache (GpuMaterialCache).
     */
    void render(Camera camera, Runnable prepassStage, Runnable colourStage) {
        render(camera, prepassStage, colourStage, true);
    }

    private void render(Camera camera, Runnable prepassStage, Runnable colourStage, boolean view) {
        // A changed lighting configuration returns every surface to its generic program before anything draws.
        if (familyPrograms.configure(environment)) {
            terrainBatch.clear();
            startFamilySession();
        }
        // Cosmetic relief layers are Enhanced-only. Routes belong to both graphics modes.
        boolean terrain = !installedColumns;
        // What the Tactical View hides (setFluffHidden).
        boolean fluff = !fluffHidden;
        // Upload before the render context tracks bound texture units for this pass, and before the fill reads the
        // fields' (cachedField); Classic's ice reads the biomes' too.
        biomes.update(coverScene);
        // The ground material cache fills the pages this view needs before anything draws (its own passes). Its
        // programs draw only when the warm-up linked every program of this lighting, the direct ones included.
        materialCache.begin(camera, terrain && materialsReady(), view && fluff, this::drawCacheTops);
        cacheChunks(camera);
        materialCache.end();
        shadingPass++;
        packKeptProps();
        updateDetail(camera);
        lavaLighting.update(coverScene, terrain);
        if (terrain) {
            groundDamage.update(coverScene);
            surfaceScars.update(coverScene);
            surfaceScars.prepare(this::scarSources, this::scarSources, scarSurfaceRevision);
            groundDamage.upload();
        }
        batch.begin(camera);
        trees.begin(GpuTreeInstances.Pass.COLOUR);
        propBatch.begin();
        terrainPages.begin(camera);
        topPages.begin(camera);
        for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
            Chunk chunk = chunks.get(chunkIndex);
            boolean visible = camera.frustum.boundsInFrustum(chunk.bounds);
            boolean scatterVisible = terrain && fluff && visible && chunk.scatterVisible;
            Hidden hidden = hidden(chunk);
            boolean packed = !hidden.props();
            // The 3D relief, or the Classic columns, in the chunk's opaque layer.
            int page = terrainPage(chunkIndex);
            terrainPages.add(chunk.terrainRenderables, page, visible && hidden.ground() == null);
            topPages.add(chunk.topRenderables, page, visible && hidden.ground() == null);
            if (visible && hidden.ground() != null) { renderRetained(hidden.ground(), batch, environment); }
            int propPage = propPage(chunkIndex);
            // Hover/occupancy changes only this chunk's presentation, not its static page membership.
            // Keep the complete opaque source cached and draw the opened chunk separately. The Tactical View keeps
            // the pages too, unseen, and draws the chunk's few kept props on their own.
            propBatch.add(chunk.shadowProps, propPage, visible && packed && fluff && chunk.faded.isEmpty());
            if (visible && packed && fluff && !chunk.faded.isEmpty()) { propBatch.addDynamic(chunk.solidProps); }
            if (visible && packed && !fluff) { propBatch.addDynamic(chunk.kept); }
            propBatch.add(chunk.scatterRenderables, propPage, scatterVisible);
            propBatch.add(chunk.routeRenderables, propPage, visible && packed);
            if (visible && !packed) { renderRetainedProps(chunk, batch, environment, false); }
            if (terrain) {
                trees.add(chunk.trees(fluff), chunk.treeLod, visible);
                trees.add(chunk.scatterStand, chunk.treeLod, scatterVisible);
            }
            if (!visible) {
                continue;
            }
            // Shared building modules are buildings: the Tactical View hides them.
            if (packed) {
                if (fluff) { trees.add(chunk.sharedProps); }
                addDecorations(chunk, batch, environment, false);
            }
        }
        if (lifts != null) { lifts.render(batch, environment); }
        // Trees first: the opaque sorter keeps this order of shaders, so the ground hidden under crowns fails the depth
        // test instead of being shaded and painted over.
        batch.render(trees, environment);
        // One share of the frame for every page merge: props, terrain, scars and paint (GpuMeshPage.BUILD_NANOS).
        long pageDeadline = System.nanoTime() + GpuMeshPage.BUILD_NANOS;
        propBatch.render(batch, environment, pageDeadline);
        terrainPages.render(batch, environment, pageDeadline);
        // A page's tops take their cached twins once the cache has filled every page under them.
        topPages.render(batch, environment, pageDeadline, materialCache::material);
        // The depth pre-pass: hidden colour fragments then fail the depth test before they shade. Not in the Tactical View
        // or on the Classic columns. The gather, the draw order and both flushes stay as they were.
        if (prepassStage != null) { prepassStage.run(); }
        prepassed = terrain && fluff && renderPrepass(camera);
        try {
            if (colourStage != null) { colourStage.run(); }
            // Opaque ground cover first, over the pre-pass's depth: the ground it hides then fails the depth test instead
            // of being shaded and painted over, as under tree crowns.
            if (terrain && fluff) { renderCover(camera); }
            batch.end();
        } finally {
            // Its last draw may have tested EQUAL: passes that enable depth testing directly find the usual LEQUAL.
            if (prepassed) { Gdx.gl.glDepthFunc(GL20.GL_LEQUAL); }
            prepassed = false;
        }
        batch.begin(camera);
        if (terrain && fluff && combatScars) {
            surfaceScars.render(camera, batch, environment, coverScene.height(), this::scarShown, pageDeadline);
        }
        paintPages.begin(camera);
        for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
            Chunk chunk = chunks.get(chunkIndex);
            boolean visible = camera.frustum.boundsInFrustum(chunk.bounds);
            if (visible) {
                // Roads and shores stay in the Tactical View; decals hide there. A chunk holds the decals of one
                // graphics only: Enhanced paint, or the Classic tileset decals.
                Array<Renderable> overlays = hidden(chunk).overlays();
                if (overlays != null) { renderRetained(overlays, batch, environment); }
                else { chunk.overlays.forEach(instance -> batch.render(instance, environment)); }
                if (fluff) {
                    chunk.surfaceDecals.forEach(instance -> batch.render(instance, environment));
                    chunk.tilesetDecals.forEach(instance -> batch.render(instance, environment));
                }
            }
            if (chunk.paintRenderables == null) { chunk.paintRenderables = GpuTerrainDepth.snapshot(chunk.authoredPaint); }
            Array<Renderable> paint = hidden(chunk).paint();
            paintPages.add(chunk.paintRenderables, terrainPage(chunkIndex), visible && fluff && paint == null);
            if (visible && fluff && paint != null) { renderRetained(paint, batch, environment); }
        }
        if (lifts != null) { lifts.renderOverlays(batch, environment, fluff); }
        paintPages.render(batch, environment, pageDeadline);
        batch.end();
    }

    /**
     * The depth pre-pass: each gathered opaque draw with a depth twin (twin) draws it first, with colour writes off and
     * this frame's gather, instance data and render state; the colour draws then test their own depth
     * (prepassDepthFunction) without writing it (bindMaterial). Every other draw is unchanged. Whether a twin drew.
     */
    private boolean renderPrepass(Camera camera) {
        prepassDraws = 0;
        if (depthPrepass.isEmpty() && environmentLighting.ambientOcclusion == null) { return false; }
        for (Renderable part : batch.gathered()) {
            Shader twin = twin(part.shader, part.material);
            if (twin != null) {
                prepassParts.add(part);
                prepassOrder.add(twin);
            }
        }
        if (prepassParts.isEmpty()) { return false; }
        RenderContext context = batch.getRenderContext();
        Gdx.gl.glColorMask(false, false, false, false);
        try {
            // Twin by twin, each in gathered order: few program switches, and depth alone does not depend on order.
            for (int first = 0; first < prepassParts.size; first++) {
                Shader twin = prepassOrder.get(first);
                if (twin == null) { continue; }
                twin.begin(camera, context);
                try {
                    for (int i = first; i < prepassParts.size; i++) {
                        if (prepassOrder.get(i) != twin) { continue; }
                        twin.render(prepassParts.get(i));
                        prepassOrder.set(i, null);
                        prepassDraws++;
                    }
                } finally { twin.end(); }
            }
        } finally {
            Gdx.gl.glColorMask(true, true, true, true);
            prepassParts.clear();
            prepassOrder.clear();
        }
        return true;
    }

    /** Whether the installed scene has fields: their plants stand with or without grass, and further out than it. */
    private boolean fields() {
        if (fieldsScene != coverScene) {
            fieldsScene = coverScene;
            fields = coverScene != null
                  && coverScene.tiles().stream().anyMatch(tile -> BoardBiome.kind(tile) == BoardScene.Biome.FIELD);
        }
        return fields;
    }

    /**
     * Ground cover in its own batch (coverBatch), over the pre-pass's depth and before the terrain's colour: adding grass
     * to the colour batch would end its material/page batching.
     */
    private void renderCover(Camera camera) {
        if (coverScene == null) { return; }
        boolean coverVisible = GpuGroundCover.visibleAtScale(camera, grass, fields());
        // At a scale where no cover draws, no hexes are gathered; the empty list still releases the plants' buffers.
        List<BoardScene.Tile> candidates = new ArrayList<>();
        if (coverVisible || GpuBiomeVegetation.visibleAtScale(camera)) {
            BoundingBox guard = new BoundingBox();
            float margin = BoardGeometry.width() * 2;
            for (int index = 0; index < chunks.size(); index++) {
                guard.set(chunks.get(index).bounds);
                guard.min.add(-margin, -margin, 0);
                guard.max.add(margin, margin, 0);
                guard.update();
                if (camera.frustum.boundsInFrustum(guard)) { chunkTiles(coverScene, index, candidates); }
            }
        }
        coverBatch.begin(camera);
        if (coverVisible) {
            groundCover.showGrass(grass);
            for (ModelInstance instance : groundCover.visible(coverScene, camera, candidates, this::planted)) {
                coverBatch.render(instance, environment);
            }
        }
        for (ModelInstance instance : biomeVegetation.visible(coverScene, camera, candidates, this::planted)) {
            coverBatch.render(instance, environment);
        }
        coverBatch.end();
    }

    /** Bytes of shared tree/building geometry and instance data held on the GPU; see {@link GpuTreeInstances#bytes()}. */
    long treeGeometryBytes() {
        return trees.bytes();
    }

    /** Tree/building instance buffer uploads so far; see {@link GpuTreeInstances#uploads()}. */
    long treeInstanceUploads() {
        return trees.uploads();
    }

    /** Water and faded features follow units, with depth testing but no depth writes. */
    void renderTransparent(Camera camera) {
        shadingPass++;
        waterDepth.invalidate();
        if (fluffHidden) {
            // The Tactical View draws no water, falls or spores: the ground layer shades the water by depth.
            batch.begin(camera);
            for (Chunk chunk : chunks) {
                if (!camera.frustum.boundsInFrustum(chunk.bounds)) { continue; }
                for (Prop prop : chunk.faded) {
                    if (drawn(prop, false)) { batch.render(prop.instance(), environment); }
                }
            }
            batch.end();
            return;
        }
        updateDetail(camera);
        biomes.update(coverScene);
        boolean hasWater = waterVisible() && chunks.stream().anyMatch(chunk -> chunk.waterField != null);
        if (hasWater) {
            waterExposure.update(coverScene);
            // The water goes on beyond the board: a view through it that leaves the board meets a board-edge section's
            // far side, which covers the sky there before the faded features and the water blend over it.
            batch.begin(camera);
            for (Chunk chunk : chunks) {
                if (!camera.frustum.boundsInFrustum(chunk.bounds)) { continue; }
                for (Renderable part : chunk.beyondRenderables) {
                    part.shader = null; part.environment = environment; batch.render(part);
                }
            }
            batch.end();
        }
        waterPages.begin();
        int pageRows = (chunkRows + GpuWaterPages.CHUNKS_PER_PAGE - 1) / GpuWaterPages.CHUNKS_PER_PAGE;
        batch.begin(camera);
        for (int index = 0; index < chunks.size(); index++) {
            Chunk chunk = chunks.get(index);
            boolean visible = camera.frustum.boundsInFrustum(chunk.bounds);
            int page = index / chunkRows / GpuWaterPages.CHUNKS_PER_PAGE * pageRows
                  + index % chunkRows / GpuWaterPages.CHUNKS_PER_PAGE;
            waterPages.add(chunk.waterRenderables, page, visible, waterVisible());
            if (visible) {
                for (Prop prop : chunk.faded) { if (drawn(prop, false)) { batch.render(prop.instance(), environment); } }
                for (ModelInstance pane : chunk.glass) { batch.render(pane, environment); }
            }
        }
        batch.end();
        if (!hasWater) {
            // Without open water there is no field atlas or depth prepass, but lake ice still draws. The pages must
            // still take this frame's sources: a page's mesh otherwise keeps the chunks of the last wet frame, whose
            // water models a partial rebuild has already disposed.
            waterPages.prepare();
            batch.begin(camera);
            waterPages.render(batch, environment, false);
            batch.end();
            waterPages.dispose();
            renderSceneryEffects(camera);
            return;
        }
        waterPages.prepare();
        // Waves need to hide the water behind them at grazing angles. This cheap pass shares both vertices and
        // displacement with the colour pass, and writes the nearest wave or board-edge section into a depth target of
        // its own: the colour pass tests against it, fog stops at it, and the scene's depth keeps the beds for outlines
        // and overlays.
        int scene = waterDepth.begin();
        try {
            batch.begin(camera);
            waterPages.render(batch, environment, true);
            batch.end();
        } finally { Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, scene); }
        batch.begin(camera);
        waterPages.render(batch, environment, false);
        batch.end();
        renderSceneryEffects(camera);
    }

    private void renderSceneryEffects(Camera camera) {
        fungus.begin();
        geysers.begin();
        for (Chunk chunk : chunks) {
            fungus.add(chunk.spores, camera);
            geysers.add(chunk.geysers, camera);
        }
        fungus.render(camera, clock, wind);
        geysers.render(camera, clock, gravity, wind, atmosphere == null ? Color.WHITE : atmosphere.groundLight());
    }

    int geyserParticles() { return geysers.particles(); }

    int fungusParticles() { return fungus.particles(); }

    long fungusClouds() {
        return chunks.stream().flatMap(chunk -> chunk.spores.stream()).filter(GpuFungus.Emitter::cloud).count();
    }

    private void updateLight(BoardScene.Light next) {
        light = next;
        shadowMap.invalidate();
        fitShadowBounds();
        applyLight();
    }

    private void fitShadowBounds() {
        shadowBounds.inf();
        chunks.forEach(chunk -> shadowBounds.ext(chunk.bounds));
    }

    void setAtmosphere(BoardAtmosphere.Lighting next) {
        // Color and fog cannot change a shadow's geometry. The light is applied every time: it is cheap, and Color
        // equality rounds to 8 bits, too coarse for night light and wrong above 1.
        if (atmosphere == null || next == null || !atmosphere.direction().equals(next.direction())) {
            shadowMap.invalidate();
        }
        atmosphere = next;
        applyLight();
    }

    /** Live shading preview; changing it needs no geometry, atlas or shadow rebuild. */
    void setNormalMaps(boolean enabled) {
        if (normalMaps != enabled) { materialCache.invalidateAll(); }
        normalMaps = enabled;
    }

    /** The sculpted terrain's heightmap parallax (terrain-materials.glsl), apart from the normal maps. */
    void setTerrainParallax(boolean enabled) {
        if (terrainParallax != enabled) { materialCache.invalidateAll(); }
        terrainParallax = enabled;
    }

    /**
     * Live grass visibility (the Grass blades option): the meadows' grass and their banks' rims, not the fields' plants.
     * Hidden grass skips its buffers and drawing without rebuilding the terrain.
     */
    void setGrass(boolean enabled) {
        grass = enabled;
    }

    /**
     * Test hook for Object LoD, which the game keeps on: whether objects under a quarter hex hide while a hex is under
     * {@link TreeLod#SMALL_OBJECTS} pixels. A switch redraws the shadows at once; a zoom crossing waits for the next redraw.
     */
    void setSmallObjects(boolean enabled) {
        if (smallObjects == enabled) { return; }
        smallObjects = enabled;
        detailPixelsPerUnit = Float.NaN;
        shadowMap.invalidate();
    }

    /** The editor objects that draw and pick at every zoom; derived from the editor's published selection and hover. */
    void keepShown(Set<String> ids) {
        if (!pinned.equals(ids)) { pinned = Set.copyOf(ids); }
    }

    /**
     * Whether this pass draws this prop: the Tactical View only what it keeps ({@link #setFluffHidden}), and what the
     * zoom shows ({@link #shown}).
     * <p>
     * KEEP IN SYNC with the packed caches: the Tactical View draws each chunk's kept props only (Chunk#keptProps). No
     * packed prop is small: only placed objects are, and they are never packed.
     */
    private boolean drawn(Prop prop, boolean shadows) {
        return !replaced(prop) && (!fluffHidden || prop.tactical || prop.route()) && shown(prop, shadows);
    }

    /**
     * Whether the zoom shows this prop, the rule picking follows in every view: small objects hide while zoomed out, but
     * selected and hovered editor objects stay shown, though never in the shadow map.
     */
    private boolean shown(Prop prop, boolean shadows) {
        return !prop.small || !smallHidden
              || !shadows && prop.decorationId != null && !pinned.isEmpty() && pinned.contains(prop.decorationId);
    }

    /** A combat scar on a prop draws only while the prop does; scars on the terrain always draw. */
    private boolean scarShown(GpuSurfaceScars.Receiver receiver) {
        var coords = receiver.coords();
        if (receiver.prop() < 0 || !smallHidden || coverScene.tile(coords) == null) { return true; }
        var tile = chunks.get(coords.getX() / CHUNK_SIZE * chunkRows + coords.getY() / CHUNK_SIZE).tileMeshes.get(coords);
        return tile == null || receiver.prop() >= tile.props.size() || drawn(tile.props.get(receiver.prop()), false);
    }

    /**
     * A chunk's placed object parts for this pass, in every view. The parts of an object the zoom hides are skipped;
     * outside the shadow pass so are faded objects, which {@link #renderTransparent} draws, while a hovered structure's
     * object keeps its part below the cut opaque ({@code hoverOpaque}).
     * <p>
     * KEEP IN SYNC with the packed caches of buildings and scenery in the same passes: Chunk#solidProps and #shadowProps
     * (#render, #renderDepth, #renderOutlineDepth). Placed objects take the same fades (Chunk#cacheProps with omitFaded)
     * and shadows that ignore fading; only they hide by size ({@link #drawn}).
     */
    private void addDecorations(Chunk chunk, ModelBatch target, Environment environment, boolean shadows) {
        for (int i = 0; i < chunk.decorationRenderables.size; i++) {
            Prop owner = chunk.decorationOwners.get(i);
            if (drawn(owner, shadows) && (shadows || !chunk.faded.contains(owner))) { trees.add(chunk.decorationRenderables.get(i)); }
        }
        for (int i = 0; i < chunk.looseDecorations.size; i++) {
            Prop owner = chunk.looseOwners.get(i);
            if (!drawn(owner, shadows) || !shadows && chunk.faded.contains(owner)) { continue; }
            Renderable part = chunk.looseDecorations.get(i);
            part.shader = null;
            part.environment = environment;
            target.render(part);
        }
        if (shadows) { return; }
        for (Prop prop : chunk.faded) {
            if (prop.decorationFeature != null && prop.hoverOpaque != null && drawn(prop, false)) {
                target.render(prop.hoverOpaque, environment);
            }
        }
    }

    void setWetness(float wetness) {
        this.wetness = wetness;
    }

    /**
     * Scenario or local preview gravity, in g. In vacuum ({@link #setVacuum}) or at zero gravity, update derives the
     * airless lunar terrain ({@link BoardScene.Tile#lunar}) without editing game state: vacuum freezes the water,
     * zero gravity removes the liquids and what lies loose.
     */
    void setGravity(float gravity) {
        float next = gravity * BoardAtmosphere.STANDARD_GRAVITY;
        // Water needs gravity, and cached wet ground keeps its wear: every top waits for pages filled since.
        if ((next > 0) != (this.gravity > 0)) {
            BoundingBox board = new BoundingBox().inf(), walls = new BoundingBox().inf();
            for (Chunk chunk : chunks) {
                board.ext(chunk.topBounds);
                union(walls, chunk.wallBounds);
            }
            materialCache.invalidate(walls);
            int since = materialCache.detailChanged(board);
            for (Chunk chunk : chunks) { chunk.cacheSince = since; }
        }
        this.gravity = next;
    }

    /** Whether the scenario or local preview air pressure is vacuum ({@code BoardAtmosphere.Settings#pressure}). */
    void setVacuum(boolean vacuum) { this.vacuum = vacuum; }

    boolean waterVisible() { return gravity > 0; }

    /** This frame's nearest water, for fog to stop at; null when the frame drew no water. */
    Texture waterDepth() { return waterVisible() ? waterDepth.nearest() : null; }

    void setWind(BoardAtmosphere.Effects effects) {
        double angle = Math.toRadians(effects.windDirection());
        wind.set((float) Math.sin(angle), (float) Math.cos(angle), effects.wind());
    }

    private void chunkTiles(BoardScene scene, int index, List<BoardScene.Tile> destination) {
        int startX = (index / chunkRows) * CHUNK_SIZE;
        int startY = (index % chunkRows) * CHUNK_SIZE;
        for (int x = startX; x < Math.min(startX + CHUNK_SIZE, scene.width()); x++) {
            for (int y = startY; y < Math.min(startY + CHUNK_SIZE, scene.height()); y++) {
                destination.add(scene.tile(new Coords(x, y)));
            }
        }
    }

    /** Isolate authored frame interpolation from surface deformation in native reference comparisons. */
    void setWaterEffects(boolean enabled) { waterEffects = enabled; }

    private GpuAssets.MaterialArrays terrainLayers() { return assets.sculptArray(SCULPT_LAYERS); }

    /** This frame's fireballs and weapon fire as local lights, binned with the board's own emitters. */
    void setEffectLights(List<GpuLavaLighting.Source> lights) { lavaLighting.setEffects(lights); }

    private void applyLight() {
        // Specular anti-aliasing is compiled in: a switch reaches the programs as a lighting change.
        GpuEnvironmentLighting.applyAntialiasing(environment);
        BoardAtmosphere.Lighting lighting = atmosphere != null ? atmosphere
              : BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS);
        environmentLighting.set(lighting);
        if (light == null && atmosphere == null) {
            shadowMap.release(environment);
            // Linear light: 0.7 shows albedo as 0.85 would in display space.
            var ambient = ColorAttribute.createAmbientLight(0.7f, 0.7f, 0.7f, 1);
            environment.set(ambient);
            environmentLighting.set(ambient.color);
            return;
        }
        // A zero-color source retains the same shader inputs in moonless/pitch-black presets. Removing the light
        // or shadow binding would rebuild every generic/family surface program despite changing no material.
        // Without an atmosphere, the scene's light direction takes the default atmosphere's light.
        GpuShadowLight shadow = shadowMap.bind(environment);
        if (atmosphere == null) {
            shadow.set(lighting.direct(), light.x() * BoardGeometry.hexScale(), light.y() * BoardGeometry.hexScale(),
                  -BoardGeometry.level());
        } else {
            shadow.set(atmosphere.direct(), atmosphere.direction());
        }
        environment.set(ColorAttribute.createAmbientLight(lighting.ambient()));
    }

    boolean hasFadedBuildings() {
        return !fluffHidden && chunks.stream().anyMatch(chunk -> !chunk.faded.isEmpty());
    }

    /** The existing opaque building caches retain occlusion for outlines even when the visible walls fade. */
    void renderOutlineDepth(Camera camera) {
        depthBatch.begin(camera);
        trees.begin(GpuTreeInstances.Pass.DEPTH);
        for (Chunk chunk : chunks) {
            if (!chunk.faded.isEmpty() && camera.frustum.boundsInFrustum(chunk.bounds)) {
                if (hidden(chunk).props()) {
                    renderRetainedProps(chunk, depthBatch, null, true);
                    continue;
                }
                depthBatch.render(chunk.shadowProps);
                // Placed objects, complete like the packed shadow caches above (KEEP IN SYNC, see addDecorations).
                addDecorations(chunk, depthBatch, null, true);
                trees.add(chunk.sharedShadows);
            }
        }
        depthBatch.render(trees);
        depthBatch.end();
    }

    /** Camera depth retains the cutaway so atmosphere effects do not hide units behind faded surfaces. */
    void renderDepth(Camera camera, List<ModelInstance> units, ModelBatch pass) {
        updateDetail(camera);
        renderDepth(camera, units, pass, false, true);
    }

    /** Wireframe fill and lines share the camera's cutaway and detail selection, without scatter terrain. */
    void renderWireframe(Camera camera, ModelBatch pass) {
        updateDetail(camera);
        renderDepth(camera, List.of(), pass, false, false);
    }

    private void renderDepth(Camera camera, List<ModelInstance> units, ModelBatch pass, boolean shadows,
          boolean includeScatter) {
        packKeptProps();
        pass.begin(camera);
        try {
            trees.begin(shadows ? GpuTreeInstances.Pass.SHADOW : GpuTreeInstances.Pass.DEPTH);
            // What the Tactical View hides casts nothing there (setFluffHidden).
            boolean fluff = !fluffHidden;
            // The shadow map takes its casters' detail from its own texel, not from the camera's pixels: trees and
            // scatter at the level their size in texels needs (TreeLod's thresholds), and scatter only where the map
            // resolves it, as well as where the view shows it. A map's casters are then the same wherever the view
            // moves at one texel size. A map whose texel spans half a metre or more cannot resolve leaves: its trees
            // cast the shadow of their second decimated level at most. Alpha-tested foliage drawn into such a map at the
            // near level was fragment-bound, 440-510 ms per refit on an Intel Iris Xe over MesaCity 1's woods.
            float texels = shadows ? shadowMap.resolution() / Math.min(camera.viewportWidth, camera.viewportHeight) : 0;
            int coarsest = shadows && 1 / texels >= BoardRelief.metres(.5f) ? 2 : 0;
            for (Chunk chunk : chunks) {
                boolean visible = camera.frustum.boundsInFrustum(chunk.bounds);
                boolean scatterVisible = includeScatter && fluff && visible && chunk.scatterVisible
                      && (!shadows || chunk.scatterDiameter * texels >= 2);
                int level = shadows ? Math.max(TreeLod.level(chunk.treeDiameter * texels, 0), coarsest) : chunk.treeLod;
                trees.add(chunk.trees(fluff), level, visible);
                trees.add(chunk.scatterStand, level, scatterVisible);
                if (visible) {
                    Hidden hidden = hidden(chunk);
                    if (hidden.ground() != null) { renderRetained(hidden.ground(), pass, null); }
                    else { pass.render(shadows ? chunk.depthTerrain.shadows : chunk.depthTerrain); }
                    if (scatterVisible) {
                        chunk.scatter.forEach(pass::render);
                    }
                    if (hidden.props()) { renderRetainedProps(chunk, pass, null, shadows); }
                    else {
                        pass.render(!fluff ? chunk.kept : shadows ? chunk.shadowProps : chunk.solidProps);
                        addDecorations(chunk, pass, null, shadows);
                        pass.render(chunk.routes);
                        if (fluff) { trees.add(shadows ? chunk.sharedShadows : chunk.sharedProps); }
                    }
                }
            }
            pass.render(trees);
            if (lifts != null && !shadows) { lifts.render(pass, null); }
            renderDepthUnits(camera, units, pass);
        } finally { pass.end(); }
    }

    private void renderDepthUnits(Camera camera, List<ModelInstance> units, ModelBatch pass) {
        for (ModelInstance unit : units) {
            if (camera.frustum.boundsInFrustum(unitBounds(unit))) { GpuUnitInstance.renderDepth(pass, unit); }
        }
    }

    void renderShadows(List<ModelInstance> units) {
        renderShadows(null, units);
    }

    void renderShadows(Camera view, List<ModelInstance> units) {
        if (view != null) { updateDetail(view); }
        if (lifts != null) {
            // Moving lifts cast like units, over the cached terrain's shadow, which leaves them out.
            units = new ArrayList<>(units);
            units.addAll(lifts.models);
        }
        shadowMap.render(view, units, shadowBounds, environment.shadowMap != null,
              atmosphere == null || atmosphere.hasDirectLight());
    }


    /** All passes use the viewing camera's detail selection; scatter culling never rebuilds a mesh. */
    private void updateDetail(Camera camera) {
        boolean perspective = camera.projection.val[Matrix4.M33] == 0;
        float pixelsPerUnit = perspective ? Float.NaN : BoardCamera.pixelsPerUnit(camera);
        if (pixelsPerUnit == detailPixelsPerUnit) {
            return;
        }
        detailPixelsPerUnit = pixelsPerUnit;
        // Small placed objects hide while a hex is under TreeLod.SMALL_OBJECTS pixels; perspective views keep them, like
        // grass. A crossing alone does not redraw the shadow map: the objects and their shadows are at most a few
        // pixels, and a forced redraw would drop the cached board-wide shadow on every zoom-out.
        smallHidden = smallObjects && !perspective && TreeLod.level(TreeLod.SMALL_OBJECTS,
              BoardGeometry.width() * pixelsPerUnit, smallHidden ? 1 : 0) == 1;
        // Further out, building shells' twins cost more than the shading they save; the twins stay built.
        objectsFar = !perspective && TreeLod.level(TreeLod.PREPASS_OBJECTS, BoardGeometry.width() * pixelsPerUnit,
              objectsFar ? 1 : 0) == 1;
        Vector3 nearest = new Vector3();
        for (Chunk chunk : chunks) {
            if (perspective) {
                // A chunk uses its nearest possible depth, preserving detail for all of its trees and scatter.
                nearest.set(camera.direction.x >= 0 ? chunk.bounds.min.x : chunk.bounds.max.x,
                      camera.direction.y >= 0 ? chunk.bounds.min.y : chunk.bounds.max.y,
                      camera.direction.z >= 0 ? chunk.bounds.min.z : chunk.bounds.max.z);
                pixelsPerUnit = BoardCamera.pixelsPerUnit(camera, nearest);
            }
            float scatterPixels = chunk.scatterDiameter * pixelsPerUnit;
            // Scatter this small casts a shadow under a texel: its appearance waits for the next refit.
            chunk.scatterVisible = scatterPixels >= (chunk.scatterVisible ? 2 : 3);
            boolean packedLevels = false, objectLevels = false;
            for (Prop prop : chunk.cutaways) {
                if (prop.detail(pixelsPerUnit)) {
                    if (prop.decorationFeature != null) { objectLevels = true; } else { packedLevels = true; }
                }
            }
            // Buildings repack the chunk's prop caches (which also gather its placed objects again); placed objects alone
            // only gather their instanced parts again. The rebuilt caches serve the next shadow refit; an outline is the
            // same at every level, so the map is not redrawn for the change alone (200-400 ms per redraw on an Intel Iris
            // Xe when the fit spans MesaCity 1, and its 233 building hexes change level throughout a tilt).
            if (packedLevels) {
                recacheProps(chunk);
            } else if (objectLevels) {
                scarSurfaceRevision++;
                chunk.cacheDecorations();
            }
            // Largest tree wins: smaller neighbors may retain extra detail, never lose it early. The shadow map chooses
            // its own levels from its texel (renderDepth).
            chunk.treeLod = TreeLod.level(chunk.treeDiameter * pixelsPerUnit, chunk.treeLod);
        }
    }

    /** Whether the board has water, where a falling unit splashes (GpuWaterImpacts). */
    boolean hasWater() { return chunks.stream().anyMatch(chunk -> !chunk.water.isEmpty()); }

    /**
     * Links the spore and geyser programs now (GpuBattleView's warm-up) on a board that has them, not when the first
     * comes into view; in the editor, which a stroke may give them, always, with the wave simulations of water and lava.
     */
    void prepareEffects() {
        if (editable || chunks.stream().anyMatch(chunk -> !chunk.spores.isEmpty())) { fungus.prepare(); }
        if (editable || chunks.stream().anyMatch(chunk -> !chunk.geysers.isEmpty())) { geysers.prepare(); }
        if (editable) {
            ocean.prepare();
            lavaOcean.prepare();
        }
    }

    @Override
    public void dispose() {
        // The kinds' renderer borrows this terrain's assets.
        if (kinds != null) { kinds.dispose(); kinds = null; }
        if (lifts != null) { lifts.dispose(); lifts = null; }
        kindScenes.clear();
        meshGeneration++;
        detailWorker.shutdownNow();
        // Workers borrow chunk/asset data. Join them before freeing it; a slow cancellation or an interrupted
        // closing thread must not abandon GL cleanup. ExecutorService.close also restores the caller's interrupt.
        detailWorker.close();
        familyPrograms.dispose();
        if (terrainMaterialPreparation != null) { terrainMaterialPreparation.dispose(); }
        terrainMaterialSamples.forEach(sample -> sample.meshPart.mesh.dispose());
        terrainMaterialSamples.clear();
        retiredChunks.forEach(Chunk::dispose);
        retiredChunks.clear();
        if (rebuild != null) { rebuild.replacements.values().forEach(Chunk::dispose); }
        for (DetailJob job : pending) { if (job.build() != null) { job.build().chunk.dispose(); } }
        pending.clear();
        rebuild = null;
        requested = null;
        if (detailJob != null && detailJob.build() != null) { detailJob.build().chunk.dispose(); }
        detailJob = null;
        detailCache.values().forEach(Chunk::dispose);
        detailCache.clear();
        terrainBatch.clear();
        groundCover.dispose();
        biomeVegetation.dispose();
        biomes.dispose();
        surfaceScars.dispose();
        groundDamage.dispose();
        scatterSource = null;
        scatterTiles = null;
        propBatch.dispose();
        fungus.dispose();
        geysers.dispose();
        terrainPages.dispose();
        topPages.dispose();
        materialCache.dispose();
        paintPages.dispose();
        waterPages.dispose();
        trees.dispose();
        pickingSurfaces.clear();
        chunks.forEach(Chunk::dispose);
        chunks.clear();
        ground.dispose();
        rims.clear();
        decals.dispose();
        batch.dispose();
        if (prepassShaders != null) { prepassShaders.dispose(); }
        // Its provider holds the fill programs only (GpuMaterialCache), which it disposes with it.
        if (fillBatch != null) { fillBatch.dispose(); }
        environmentLighting.dispose();
        lavaLighting.dispose();
        depthBatch.dispose();
        shadowMap.dispose();
        if (borrowedAssets) { assets.release(this); } else { assets.dispose(); }
        rainNoise.dispose();
        waterDetail.dispose();
        waterDepth.dispose();
        ocean.dispose();
        waterExposure.dispose();
        lavaOcean.dispose();
        featureTriangles.clear();
    }
}
