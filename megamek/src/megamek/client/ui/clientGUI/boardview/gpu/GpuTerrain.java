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
import java.util.LinkedHashSet;
import java.util.function.Predicate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
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
import com.badlogic.gdx.graphics.TextureArray;
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
import com.badlogic.gdx.graphics.g3d.utils.RenderContext;
import com.badlogic.gdx.graphics.g3d.utils.RenderableSorter;
import com.badlogic.gdx.graphics.g3d.utils.ShaderProvider;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.OrientedBoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import megamek.client.ui.clientGUI.boardview.gpu.GpuTerrainLayer.MeshBatch;
import megamek.common.board.BoardBillboard;
import megamek.common.board.BoardFlag;
import megamek.common.board.BuildingInteriors;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
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
    /** Any 2D texture, for material slots and samplers that a draw binds but never reads. */
    private final Texture rainNoise = rainNoise();
    private final TextureArray surfaceNoise = GpuWaterShader.surfaceNoise();
    /** Wind waves for every open water surface, advanced once per frame. */
    private final GpuOcean ocean = new GpuOcean();
    private final GpuWaterExposure waterExposure = new GpuWaterExposure();
    /** The nearest wave of this frame; the scene's depth keeps the beds and units beneath it. */
    private final GpuWaterDepth waterDepth = new GpuWaterDepth();
    private final GpuCutoutFade cutoutFade = new GpuCutoutFade();
    /** The editor's side view, whose size differs from the board's: one glass target between them would be rebuilt twice a frame. */
    private final GpuCutoutFade sectionFade = new GpuCutoutFade();
    private final BoundingBox fadeBounds = new BoundingBox();
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
    private final GatheringBatch batch = new GatheringBatch(GpuShaderManager.exclusiveProvider("Terrain",
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
     * also the inputs of its alpha test, so a program and its twin cover the same pixels at the same depth (a tree
     * impostor writes its texels' surface depth, the colour program a hair nearer: terrain-foliage.frag).
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
                  // Procedural water reads no authored colour: it spares that texture unit.
                  + (proceduralWater ? "" : "#define authoredWaterFlag\n")
                  + (renderable.material.has(GpuBiomeVegetation.Kind.TYPE) ? "#define biomeVegetationFlag\n" : "")
                  + (renderable.material.has(GpuSurfaceBlend.TYPE) ? "#define terrainBlendFlag\n" : "")
                  + (ordinaryFamily != -1 ? "#define VOLCANIC_ORDINARY_FAMILY " + ordinaryFamily + "\n" : "")
                  + (renderable.material.has(GpuMagmaShader.TYPE) ? "#define volcanicFlag\n" : "")
                  + (chosen == sculptShader && magma == null && plainSculptFinish()
                        ? "#define plainSculptFinishFlag\n" : "")
                  + (renderable.material.has(GpuRoads.TYPE) ? "#define roadFlag\n" : "")
                  + (renderable.material.has(GpuRoads.Mask.TYPE) ? "#define roadMaskFlag\n" : "")
                  + (renderable.material.has(GpuRoads.Coats.TYPE) ? "#define roadCoatFlag\n" : "")
                  + (renderable.material.has(GpuRoads.Soil.TYPE) ? "#define roadSoilFlag\n" : "")
                  + (renderable.material.has(BridgeDeck.TYPE) ? "#define bridgeDeckFlag\n" : "")
                  + (renderable.material.has(GpuRoads.Maps.TYPE) ? "#define roadMapsFlag\n" : "")
                  + (renderable.material.has(Impostor.TYPE) && GpuTreeInstances.instanced(renderable)
                        ? "#define impostorFlag\n" : "")
                  + (chosen == instancedFoliageShader
                        ? "#define treeDissolveFlag\n#define TREE_DISSOLVE " + TreeLod.DISSOLVE + "\n" : "")
                  // Only sculpted ground has the biome stage; the marker still keeps the environments' programs apart. A
                  // fill draws without an environment and always has it: a field's ground is in its pages, and on a board
                  // without biomes the stage finds no cover.
                  + (!renderable.material.has(Sculpt.TYPE) ? "" : pruning && !fill ? GpuBiomeSurface.prefix(renderable)
                        : "#define biomeMaskFlag\n");
            String fragment = chosen.fragmentShader;
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
                  + (GpuIceShader.mode(renderable.material) != 0 ? " ice-" + GpuIceShader.variant(renderable.material) : "")
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
                // A cutout's twin returns after its alpha test (GpuGlsl.source moves the version line above the define),
                // an instanced tree's after its dissolve too, solid parts included; another solid surface's twin has an
                // empty fragment stage.
                fragment = renderable.material.has(FloatAttribute.AlphaTest) || chosen == instancedFoliageShader
                      ? "#define depthPrepassFlag\n" + fragment : depthFragment;
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
                private final boolean levelTops = families != null && GpuFamilyPrograms.isFlat(renderable.material);
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
                private final int surfaceNoiseUniform = register("u_surfaceNoise");
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
                private final int treeClockUniform = register("u_treeClock");
                private final int shocksUniform = register("u_shocks");
                private final int shockCountUniform = register("u_shockCount");
                private final int mirroredUniform = register("u_mirrored");
                private final int metreUniform = register("u_worldMetre");
                private final int coverPixelsUniform = register("u_coverPixels");
                private final int coverShareUniform = register("u_coverShare");
                private final int coverHexWidthUniform = register("u_coverHexWidth");
                private final int gridShadeUniform = register("u_gridShade");
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
                private final int fullSizeColorUniform = register("u_fullSizeColor");
                private final int fullSizeDataUniform = register("u_fullSizeData");
                // The full-size arrays (tundra carpets, ice, lava), uploaded with the program like the finish arrays.
                // Before drawing: creating these from a uniform setter would disturb the active texture bindings.
                private final GpuAssets.MaterialArrays fullSizeMaps = program.hasUniform("u_fullSizeColor")
                      || program.hasUniform("u_fullSizeData") ? assets.fullSizeMaps() : null;
                private int fullSizeColorUnit = -1, fullSizeDataUnit = -1;
                private final int treePitMaterialUniform = register("u_treePitMaterial");
                private final int scorchedMaterialUniform = register("u_scorchedMaterial");
                private final Vector3 detailPosition = new Vector3();
                private int surfaceNoiseUnit = -1;
                private int groundDamageUnit = -1;
                private final int[] waterOceanUnits = { -1, -1, -1 };
                private int magmaOceanUnit = -1;
                private int waterShapeUnit = -1;
                private int waterExposureUnit = -1;
                private int waterNearestUnit = -1;
                private long globalsPass = -1;
                private final long instances = instanceMask(renderable);
                private final int iceMode = GpuIceShader.variant(renderable.material);
                private final boolean ruby = GpuCrystalTransmission.ruby(renderable.material);
                private final int environmentFlags = GpuEnvironmentLighting.flags(renderable.environment);
                private final VertexAttributes createdLayout = renderable.meshPart.mesh.getVertexAttributes();
                private final long vertexLayout = GpuSurfaceScars.receiverLayout(createdLayout);
                private final int textureCoordinates = createdLayout.getTextureCoordinates();

                @Override
                public boolean canRender(Renderable other) {
                    // libGDX binds only the instance attributes of the renderable a shader was created for (instanceMask).
                    // Family programs (GpuFamilyPrograms) first: the provider's scan rejects them cheaply.
                    return GpuFamilyPrograms.draws(families, baseAliases, levelTops, other.material, Sculpt.TYPE)
                          && GpuMaterialCache.isCached(other.material) == cached
                          && (!fill && !cached
                                || GpuMaterialCache.charted(other.meshPart.mesh.getVertexAttributes()) == charted)
                          && sameMaterialVariant(other.material, iceMode, linearTexture, ordinaryFamily, waterMode,
                                flowingMagma, ruby)
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
                    set(treeClockUniform, clock);
                    // The live blasts' fronts: plants bend and sway, scorch spreads, as each passes (terrain-shocks.glsl).
                    set(shockCountUniform, shockCount);
                    if (shockCount > 0 && has(shocksUniform)) { program.setUniform4fv(loc(shocksUniform), shocks, 0, shockCount * 4); }
                    set(metreUniform, BoardRelief.detailMetres(1));
                    set(coverHexWidthUniform, BoardGeometry.width());
                    set(coverShareUniform, BoardQuality.current.meadowShare, BoardQuality.current.cropShare);
                    // Scene pixels: the render resolution's target, not the window's.
                    set(coverPixelsUniform, BoardGeometry.width()
                          * Math.abs(camera.projection.val[Matrix4.M11]) * camera.viewportHeight * .5f
                          * Gdx.graphics.getBackBufferHeight() * BoardCamera.renderScale / Math.max(1f, Gdx.graphics.getHeight()));
                    set(gridShadeUniform, BoardGeometry.tuning().gridShade());
                    set(levelUniform, BoardGeometry.level());
                    // Authored scorch also shows where combat scars do not (the editor, its previews).
                    boolean damaged = combatScars && groundDamage.marks() > 0
                          && groundDamage.texture() != null;
                    set(groundDamageSizeUniform, damaged ? groundDamage.width() : 0, damaged ? groundDamage.height() : 0);
                    set(groundDamageGridUniform, groundDamage.tileWorldSize(), (float) groundDamage.tileColumns(), (float) groundDamage.tileRows());
                    boolean scorched = groundDamage.scorchShown() && groundDamage.texture() != null;
                    set(groundScorchUniform, scorched ? 1f : 0f, scorched ? groundDamage.scorchHeat() : 0f);
                    set(sculptMetreUniform, BoardRelief.metres(1));
                    set(waterLineUniform, BoardGeometry.hexScale());
                    set(waterExposureMapUniform, waterExposure.mapping[0], waterExposure.mapping[1],
                          waterExposure.mapping[2], waterExposure.mapping[3]);
                    set(wavePixelsUniform, BoardGeometry.width() * Math.abs(camera.projection.val[Matrix4.M11])
                          * Gdx.graphics.getBackBufferHeight() * BoardCamera.renderScale * .5f);
                    float waveStart = Math.max(8, TerrainLod.tuning().threshold(2) * 1.1f);
                    set(waveFadeUniform, waveStart, Math.max(50, waveStart + 1));
                    if (has(biomeBoardUniform) || has(iceBoardUniform)) {
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
                        float[] floors = new float[2 * FLOORS.length];
                        for (int family = 0; family < FLOORS.length; family++) {
                            String floor = FLOORS[family];
                            floors[2 * family] = floor == null ? -1 : 2f * SCULPT_LAYERS.indexOf(floor);
                            floors[2 * family + 1] = floor == null ? 0 : assets.sculptTile(floor);
                        }
                        program.setUniform2fv("u_floorMaterials[0]", floors, 0, floors.length);
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
                    // A tree impostor writes its own, a hair nearer than its twin's (terrain-foliage.frag), so the frame
                    // ends with the depths it has without the pre-pass.
                    if (prepassed && twin(this, attributes) != null) {
                        context.setDepthTest(prepassDepthFunction);
                        context.setDepthMask(impostor(attributes));
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
                    surfaceNoiseUnit = bindShared(surfaceNoiseUniform, surfaceNoise, surfaceNoiseUnit);
                    if (fullSizeMaps != null) {
                        fullSizeColorUnit = bindShared(fullSizeColorUniform, fullSizeMaps.color(), fullSizeColorUnit);
                        fullSizeDataUnit = bindShared(fullSizeDataUniform, fullSizeMaps.data(), fullSizeDataUnit);
                    }
                    if (has(groundDamageUniform)) {
                        groundDamageUnit = bindShared(groundDamageUniform, groundDamage.binding(), groundDamageUnit);
                    }
                    for (int cascade = 0; cascade < waterOceanUniforms.length; cascade++) {
                        Texture waves = ocean.waves(cascade);
                        waterOceanUnits[cascade] = bindShared(waterOceanUniforms[cascade],
                              waves == null ? rainNoise : waves, waterOceanUnits[cascade]);
                    }
                    if (has(waterShapeUniform)) {
                        Texture shape = ocean.displacement();
                        waterShapeUnit = bindShared(waterShapeUniform, shape == null ? rainNoise : shape, waterShapeUnit);
                    }
                    if (has(waterExposureUniform)) {
                        waterExposureUnit = bindShared(waterExposureUniform, waterExposure.texture(), waterExposureUnit);
                    }
                    if (has(waterNearestUniform) && waterDepth.texture() != null) {
                        waterNearestUnit = bindShared(waterNearestUniform, waterDepth.texture(), waterNearestUnit);
                    }
                    if (has(magmaOceanUniform)) {
                        Texture waves = lavaOcean.texture();
                        magmaOceanUnit = bindShared(magmaOceanUniform, waves == null ? rainNoise : waves, magmaOceanUnit);
                    }
                    if (camera.projection.val[Matrix4.M33] == 0) {
                        detailPosition.set(part.meshPart.center).mul(part.worldTransform);
                        setDetailUniforms();
                    }
                    // A mirror image drawn as it is (not instanced) reverses its winding: its visible faces are back faces.
                    if (has(mirroredUniform)) { set(mirroredUniform, part.worldTransform.det3x3() < 0 ? 1f : 0f); }
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
            GpuLavaLighting.register(result, lavaLighting);
            GpuTreeInstances.registerPiece(result);
            GpuAmbientOcclusion.register(result);
            if (tessellated) {
                GpuTessellation.tessellated(result.program);
                GpuTessellation.register(result);
            }
            GpuModelMaterial.register(result);
            GpuModelLight.register(result, () -> clock);
            GpuSurfaceScars.register(result);
            if (fill) { materialCache.registerFill(result); }
            if (cached) { materialCache.registerCached(result, charted); }
            if (!fill && !depthOnly) { materialCache.registerDebug(result, cached); }
            GpuLiquidShader.register(result);
            GpuMagmaShader.register(result, rainNoise);
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
                "shadow depth")), this::registerTables)),
          new GpuOpaqueSorter());
    private final Map<ModelInstance, Integer> flagRevisions = new java.util.IdentityHashMap<>();
    /** Bumped when installed chunks change, the ground under the flags' grids (GpuFlag.ground). */
    private int flagScene;
    private int flagFrame;
    /** The view last drawn: cloth outside it holds its pose. Null until one draws, or for a view-less render. */
    private Camera flagView;
    private boolean waveFrame;
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
        public BoundingBox bounds(ModelInstance unit) {
            if (unit != fallCaster) { return unitBounds(unit); }
            float reach = BoardGeometry.width();
            BoundingBox bounds = destruction.bounds(new BoundingBox());
            return bounds.set(bounds.min.sub(reach, reach, reach), bounds.max.add(reach, reach, 0));
        }

        @Override
        public int revision(ModelInstance unit) {
            return unit == fallCaster ? Float.floatToIntBits(destruction.clock()) : flagRevisions.getOrDefault(unit, 0);
        }
    });

    void shadersChanged() { refreshShadows(); }

    /** The local lights' texture and the falls in it, for a shadow program: falling pieces cast where they are. */
    private void registerTables(DepthShader shader) {
        GpuLavaLighting.register(shader, lavaLighting);
        // Standing trees sway in a blast's wind in the depth passes too (terrain-shocks.glsl).
        shader.register("u_shockCount", new BaseShader.GlobalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                target.set(id, shockCount);
            }
        });
        shader.register("u_shocks", new BaseShader.GlobalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                if (shockCount > 0 && target.has(id)) { target.program.setUniform4fv(target.loc(id), shocks, 0, shockCount * 4); }
            }
        });
    }

    /** What selects a sculpt material's generic program besides its families; the family marker is left out. */
    private static Object familyVariant(Material material) {
        return List.of(material.getMask() & ~GpuFamilyPrograms.Constants.TYPE, GpuIceShader.variant(material),
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
              && (category != Prepass.RELIEF || environmentLighting.ambientOcclusion != null || reliefFar)
              ? prepassTwins.get(shaderGroup(colour)) : null;
    }

    /**
     * Whether the depth pre-pass draws a category: those of depthPrepass, and all relief while ambient occlusion reads
     * the pre-pass depth (GpuAmbientOcclusion in the environment's lighting, so its switch prepares the twins as a
     * lighting change) or Medium quality saves shading the relief's hidden faces.
     */
    private boolean prepasses(Prepass category) {
        return category != null && (depthPrepass.contains(category)
              || (category == Prepass.RELIEF || category == Prepass.CLIFFS)
                    && (environmentLighting.ambientOcclusion != null || environmentLighting.cheaperShading));
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
              || material.has(GpuSurfaceScars.Marking.TYPE)
              || material.has(GpuTilesetTerrain.Grading.TYPE)) { return null; }
        // terrain-foliage.frag discards by the alpha test, and an instanced tree by its dissolve, which their twins
        // repeat. Plants are GLB models too (GpuModelMaterial), so they are told apart first.
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
    /**
     * The shadow map's own tree buffers: it draws each chunk's trees at one level of its own (renderDepth), where the
     * view draws each tree at its own level, so sharing the buffers would upload them twice on every refit.
     */
    private final GpuTreeInstances shadowTrees;
    private final Environment environment = new Environment();
    private final GpuEnvironmentLighting environmentLighting = new GpuEnvironmentLighting();
    private final GpuCrystalTransmission crystalTransmission = new GpuCrystalTransmission();
    private final GpuLavaLighting lavaLighting;
    /** Kit structures falling where they stood, drawn instanced in every pass (GpuDestruction). */
    private final GpuDestruction destruction;
    /** The live nuclear blasts' shock fronts (terrain-shocks.glsl), four floats each, and how many there are. */
    private final float[] shocks = new float[GpuArtilleryBlast.MAX_SHOCKS * 4];
    private int shockCount;
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
          Map<Coords, List<BoardDecals.Stamp>> paint, boolean paintOnly,
          Map<GpuAuthoredBuilding.Layout, GpuAuthoredBuilding.Prepared> authored,
          Map<Coords, BoardSurface> plantSupports, GpuTextures<BoardScene.Pixels> roadMasks) {
        Prepared(Map<Coords, BoardSurface> surfaces, GpuWaterShader.Field.Prepared water, GpuWaterShader.Field.Prepared lava,
              Map<Coords, BoardTacticalGeometry.Surface> topography, Map<Coords, BoardPlants> plants,
              Map<Coords, SculptPlan> sculpts, Map<Coords, BoardFlow.Current> currents, Map<Coords, List<RoadPatch>> roads,
              Map<Coords, BoardBridge.Deck> bridges, Map<Coords, BoardBridge.Shape> bridgeShapes,
              Map<Coords, Map<BoardSurface.Side, List<BoardSurface.Face>>> walls, Set<Coords> reused,
              Map<Coords, List<BoardDecals.Stamp>> paint, boolean paintOnly,
              Map<GpuAuthoredBuilding.Layout, GpuAuthoredBuilding.Prepared> authored, Map<Coords, BoardSurface> plantSupports) {
            this(surfaces, water, lava, topography, plants, sculpts, currents, roads, bridges, bridgeShapes, walls, reused, paint,
                  paintOnly, authored, plantSupports, null);
        }

        /** The masks of these roads' patches, each once: a chunk's road mask atlas ({@link Chunk#roadMasks}). */
        static Map<BoardScene.Pixels, BoardScene.Pixels> roadMasks(Map<Coords, List<RoadPatch>> roads) {
            Map<BoardScene.Pixels, BoardScene.Pixels> masks = new HashMap<>();
            roads.values().forEach(patches -> patches.forEach(p -> masks.put(p.mask().pixels(), p.mask().pixels())));
            return masks;
        }

        /**
         * With a new chunk's road masks packed into their atlas's pages, on the worker that prepared it: its build only
         * uploads them (ChunkBuild). A chunk that reuses hexes packs their masks with its own, there. Whoever takes this
         * builds the chunk or calls {@link #discard}.
         */
        Prepared packed() {
            var images = reused.isEmpty() ? roadMasks(roads) : Map.<BoardScene.Pixels, BoardScene.Pixels>of();
            return images.isEmpty() ? this : new Prepared(surfaces, water, lava, topography, plants, sculpts, currents, roads,
                  bridges, bridgeShapes, walls, reused, paint, paintOnly, authored, plantSupports,
                  new GpuTextures<BoardScene.Pixels>().prepack(images));
        }

        /** Never built into a chunk (a job a newer request passed): frees the road masks packed ahead. Any thread. */
        void discard() {
            if (roadMasks != null) { roadMasks.dispose(); }
        }
    }
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
    /** The structures coming down, from the transition that brought them down until their pieces have sunk away. */
    private final List<Collapsing> collapsing = new ArrayList<>();
    private final ArrayDeque<Chunk> retiredChunks = new ArrayDeque<>();
    private final Set<Coords> atlasChanges = new HashSet<>();
    private final Set<Coords> atlasTileChanges = new HashSet<>();
    private Map<GroundSlot, BoardScene.Pixels> terrainPixels = Map.of();
    private Map<DecalSlot, BoardScene.Pixels> decalPixels = Map.of();
    private final GpuGroundCover groundCover = new GpuGroundCover();
    private final GpuBiomeVegetation biomeVegetation = new GpuBiomeVegetation();
    /** The chunks whose hexes ground cover may draw (renderCover), and the hexes of those it last listed. */
    private final java.util.BitSet coverChunks = new java.util.BitSet(), coverShown = new java.util.BitSet();
    private final BoundingBox coverGuard = new BoundingBox();
    private List<BoardScene.Tile> coverCandidates = List.of(), coverTiles;
    /** The lists last asked about fields, crystals and biomes, and the answers: a list changes only with the board. */
    private List<BoardScene.Tile> fieldsTiles, crystalTiles, biomeTiles;
    private boolean fields, biomesActive, crystals;
    private final GpuBiomeSurface biomes;
    private final GpuGroundDamage groundDamage = new GpuGroundDamage();
    private final GpuSurfaceScars surfaceScars = new GpuSurfaceScars(groundDamage);
    private long scarSurfaceRevision;
    private boolean combatScars = GpuGroundDamage.COMBAT_SCARS;
    /** Whether its kit levels break into fragments ahead of their falls (#setFragments). */
    private boolean fragments;
    /** Each tree model an attack is about to break, split into fragments (KitFragments#splitLater): render thread. */
    private final Map<Model, CompletableFuture<KitFragments>> treeSplits = new IdentityHashMap<>();
    /** A board the editor changes, and its samples: any surface may appear, so programs keep the biome stage. */
    private boolean editable;
    /** The shader warm-up's choice of the biome stage and crystal transmission (setLightingStages); null follows the board. */
    private Boolean biomeStage, crystalStage;
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
    /** {@link #featureTriangles} of the larger models bucketed for vertical rays ({@link TriangleGrid}). */
    private final Map<Model, TriangleGrid> featureGrids = new java.util.WeakHashMap<>();
    private final BoundingBox shadowBounds = new BoundingBox();
    private final UnitBounds.Frame frameBounds;
    private List<BoardScene.Tile> tiles;
    private Map<Coords, BoardFlow.Current> currents = Map.of();
    private BoardConcrete coast;
    private BoardScene.Light light;
    private BoardAtmosphere.Lighting atmosphere;
    private float clock;
    /**
     * Trees dissolve between detail levels (Stand#detail): set by the live view, whose clock runs every frame. Captures
     * and previews, whose clock stands still between frames, switch at once.
     */
    boolean treeDissolves;
    /** When the last tree dissolve ends (Stand#detail), 0 if none. */
    private float treesSettle;
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
    /** The mesh generation of the request that last joined a rebuild still preparing (update): joined once. */
    private long joinedGeneration = -1;
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
    /** The Tactical View's one draw rule ({@link #setScatterHidden}); it never rebuilds the terrain. */
    private boolean scatterHidden;
    /**
     * What the depth pre-pass draws (renderPrepass): ground relief and solid plant parts with an empty fragment stage,
     * cutout leaves and impostors with their own alpha test. Native measurements take one category at a time.
     */
    /**
     * OBJECTS: building shells' opaque model surfaces (model.frag, GpuModelMaterial.occluder), whose draw order leaves
     * overdraw. CLIFFS: the relief of a chunk whose walls pass cliffWalls; RELIEF: the relief of the other chunks.
     */
    enum Prepass { RELIEF, CLIFFS, FOLIAGE, IMPOSTORS, OBJECTS }

    /**
     * The categories the depth pre-pass draws: the trees' leaves and impostors, whose cutouts hide the most shading,
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
    /**
     * The colour pass's depth test for a draw the pre-pass drew; native measurements compare GL_EQUAL. At equal, Iris Xe
     * loses a few isolated pixels of leaves and relief, others each frame, so still frames differ.
     */
    static int prepassDepthFunction = GL20.GL_LEQUAL;
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
    /** The pile whose program stands for all rubble's, and whether it was asked for (ChunkBuild#finish). */
    private static final String RUBBLE_PILE = "scenery/rubble/medium";
    private boolean rubblePrepared;
    /** Whether this flush's colour draws follow a depth pre-pass (render, bindMaterial). */
    private boolean prepassed;
    /**
     * Whether a hex is under TreeLod.PREPASS_OBJECTS pixels on screen (updateDetail): objects then draw without their
     * twins (twin), which stay built, so a zoom links nothing. Perspective views keep them.
     */
    private boolean objectsFar;
    /**
     * Whether a hex is under TreeLod.PREPASS_RELIEF pixels on screen (updateDetail): Medium quality's relief then draws
     * its twin, whose depth spares the shading of what lies hidden; closer, little does.
     */
    private boolean reliefFar;
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

    /** The rain noise as a 2D texture, allocated once: what its readers sample is the copy in {@link #surfaceNoise}. */
    private static Texture rainNoise() {
        Pixmap pixels = GpuWaterShader.rainNoisePixels();
        try {
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
          int ordinaryFamily, GpuWaterShader.Mode waterMode, boolean flowingMagma, boolean ruby) {
        return GpuIceShader.variant(material) == iceMode && linearDiffuse(material) == linearTexture
              && GpuCrystalTransmission.ruby(material) == ruby
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
        destruction = new GpuDestruction(lavaLighting, (name, level) -> foliage(assets.lodModel(name, level)));
        environmentLighting.lavaLighting = lavaLighting;
        biomes = new GpuBiomeSurface(lavaLighting);
        trees = new GpuTreeInstances((name, level) -> foliage(assets.lodModel(name, level)));
        shadowTrees = new GpuTreeInstances((name, level) -> foliage(assets.lodModel(name, level)));
    }

    private static final class Prop {
        private List<RigidGlb.NightLight> nightLights = List.of();
        private List<GpuLavaLighting.Source> drawnLights = List.of();

        private void updateLights(int boardHeight) {
            if (nightLights.isEmpty()) { return; }
            Matrix4 transform = instance().transform;
            float sizeScale = GpuAuthoredBuilding.lightScale(transform);
            int tile = coords().getX() * boardHeight + coords().getY();
            drawnLights = nightLights.stream().map(light -> new GpuLavaLighting.Source(tile,
                  new Vector3(light.position()).mul(transform), light.power(), light.color(), light.size() * sizeScale,
                  light.end() == null ? null : new Vector3(light.end()).mul(transform), 0)).toList();
        }

        private boolean infrastructure;
        private final Coords coords;
        private final BoundingBox bounds;
        private final String treeAsset;
        /** Authored translucency, independent of temporary building fades. */
        private final boolean translucent;
        /** Whether a material of its model fades (GpuCutoutFade#fades). */
        private final boolean fades;
        /** What picking and placement hit: the near level (LOD0) whatever level draws; null for a kit assembly. */
        private final Model pickingModel;
        /** The triangles picked instead of {@link #pickingModel}'s, for an instance of only some of its nodes; or null. */
        private List<Vector3> pickingTriangles;
        /**
         * Its mesh per level, near mesh first, as the assets hand a model out ({@link GpuAssets#levels}); one mesh for a
         * model without simpler levels, and for a kit assembly, whose levels are its kit's. {@link #detail} chooses among
         * them; a tree in the stand takes the stand's levels instead.
         */
        private final List<Model> levels;
        private final float treeDiameter;
        private ModelInstance instance;
        private final boolean hardSurface;
        private final GpuBuilding.Assembly building;
        /** A building's, fuel tank's or industrial structure's shell or floor, or a placed object other than a plant. */
        private final boolean structure;
        /** Height-only industrial cover has neither occupiable storeys nor building cutaways. */
        private final boolean industrial;
        /** Cosmetic cover, including instanced fungi; excluded from wireframe fill and lines. */
        private boolean scatter;
        /** An interior floor of a structure that stands open ({@link ConnectedKit#open}): it draws without an occupant. */
        private boolean openShell;
        /** A placed object under a quarter hex, which zoomed-out views skip ({@link GpuTerrain#drawn}). */
        private boolean small;
        /**
         * A placed object's id, which editor picking, previews and pinning use in every view; null for one its terrain or
         * rail owns ({@link BoardFeatures#terrainOwned}) and for every other prop.
         */
        private String decorationId;
        private Coords decorationOwner;
        private List<Vector3> supportTriangles;
        /** The near mesh in its own frame; authored slices retain their original object-space envelope. */
        private BoundingBox editorBounds;
        private float decorationAnchorZ;
        /**
         * A placed object's feature, owned ones included: in every view its parts draw instanced
         * ({@link Chunk#cacheDecorations}), never in the packed prop caches. Null for every other prop.
         */
        private BoardScene.Feature decorationFeature;
        private megamek.common.board.BoardDecoration.Colours buildingColours = megamek.common.board.BoardDecoration.Colours.NONE;
        private BoardScene.Feature previewFeature;
        /** A stand tree's scene feature, by which a blast's felled trees are told apart (GpuTerrain#strike). */
        private BoardScene.Feature plantFeature;
        // Render-thread presentation only. Rebuilds continue borrowing the untouched installed instance/bounds.
        private ModelInstance previewInstance;
        private BoundingBox previewBounds;
        private float previewAnchorZ;
        private Coords previewCoords;
        private Coords previewOwner;
        private String receiver;
        /** Its hex's interior floors, all of them as one prop ({@link #interior}). */
        private final boolean floors;
        /** A closed kit building's shell whose floors and struts are not built yet ({@link #showInterior}). */
        private boolean interiorPending;
        /** The drawn level of its mesh ({@link #detail}). */
        private int lod;
        /** The numbered parts of its model it shows, at every level ({@link BoardGroundCut#showParts}); 0 shows them all. */
        private int parts;

        Prop(Coords coords, ModelInstance instance, List<Model> levels, BoundingBox bounds, String treeAsset,
              boolean hardSurface) {
            this(coords, instance, levels, bounds, treeAsset, hardSurface, null, false, false, false);
        }

        /**
         * {@code instance} draws one of {@code levels}, the near one unless it copies a prop that drew another. What
         * the prop is (translucent, fading, picked) is read from the near level, whichever draws.
         */
        Prop(Coords coords, ModelInstance instance, List<Model> levels, BoundingBox bounds, String treeAsset,
              boolean hardSurface, GpuBuilding.Assembly building, boolean structure, boolean floors, boolean industrial) {
            this.coords = coords;
            this.building = building;
            this.structure = structure;
            this.industrial = industrial;
            this.floors = floors;
            this.instance = instance;
            this.levels = levels;
            this.bounds = bounds;
            this.treeAsset = treeAsset;
            boolean hasBlend = false, hasFade = false;
            for (Material material : levels.getFirst().materials) {
                hasBlend |= blended(material) || GpuCrystalTransmission.ruby(material);
                hasFade |= GpuCutoutFade.fades(material);
            }
            translucent = hasBlend;
            fades = hasFade;
            this.hardSurface = hardSurface;
            pickingModel = building == null ? levels.getFirst() : null;
            treeDiameter = treeAsset == null ? 0 : bounds.getDimensions(new Vector3()).len();
        }

        /** How many mesh levels it has to choose from: its kit's, else its model's. */
        int lodCount() { return building != null ? building.lodCount() : levels.size(); }

        /**
         * The one level rule of every prop outside the tree stands: the larger side of the placed bounds, up to
         * {@link TreeLod#PROPS_SIDE}, on screen against {@link TreeLod#PROPS}, so both camera views choose alike. Picking stays on
         * the near level ({@link #pickingModel}); shadows draw the chosen level from the chunk's next cache. A tree of
         * the stand takes its level there, each by its own size (GpuTreeInstances.Stand#detail).
         */
        boolean detail(float pixelsPerUnit) {
            int count = lodCount();
            if (count < 2 || standTree()) { return false; }
            lod = Math.min(count - 1, TreeLod.prop(Math.max(bounds.getWidth(), bounds.getHeight()), pixelsPerUnit, lod));
            Model next = building != null ? building.model(lod) : levels.get(lod);
            if (instance.model == next) { return false; }
            instance = level(lod, instance);
            return true;
        }

        /**
         * This placement drawn at mesh {@code level}: {@code source}'s transform, its colours for the shared mesh of each
         * level (RigidGlb#packed) and its material state.
         */
        private ModelInstance level(int level, ModelInstance source) {
            ModelInstance result = building != null ? building.instance(level) : new ModelInstance(levels.get(level));
            BoardGroundCut.showParts(result, parts);
            result.transform.set(source.transform);
            result.userData = source.userData;
            // The placement's fade (#fade): a material that blends where its model's does not. NaN while it is solid, and
            // for a placement of cutouts and its model's own glass alone, which has no such material.
            float opacity = Float.NaN;
            for (Material material : source.materials) {
                Material own = source.model.getMaterial(material.id);
                if (blended(material) && (own == null || !blended(own))) {
                    opacity = material.get(BlendingAttribute.class, BlendingAttribute.Type).opacity;
                    break;
                }
            }
            for (Material material : result.materials) {
                Material previous = source.getMaterial(material.id);
                if (building == null && previous != null) {
                    // An object's levels share its file's materials: all of the placement's material state carries over.
                    material.clear();
                    for (Attribute attribute : previous) { material.set(attribute.copy()); }
                    continue;
                }
                // Fade state belongs to the placement and survives a visual detail change. A material the source
                // lacks (an orchard tree's fruit, on its nearer levels only) takes the placement's fade.
                Material state = previous != null ? previous : source.materials.isEmpty() ? null : source.materials.first();
                if (state == null) { continue; }
                if (previous == null) {
                    // Solid keeps the level's own blending: fade(NaN) would strip a model's own glass.
                    if (Float.isNaN(opacity)) { culling(result, material, false); }
                    else { fade(result, material, opacity); }
                    continue;
                }
                for (long type : new long[] { BlendingAttribute.Type, DepthTestAttribute.Type, IntAttribute.CullFace }) {
                    Attribute attribute = state.get(type);
                    if (attribute != null) { material.set(attribute.copy()); }
                }
            }
            GpuBuilding.tint(result, source == previewInstance && previewFeature != null ? previewFeature.colours() : buildingColours);
            return result;
        }

        /** What the editor's side view draws: the near level, whatever level the board's camera chose. */
        ModelInstance near() { return lod == 0 ? instance() : level(0, instance()); }

        Coords coords() { return previewCoords == null ? coords : previewCoords; }
        Coords owner() { return previewOwner == null ? decorationOwner : previewOwner; }
        ModelInstance instance() { return previewInstance == null ? instance : previewInstance; }
        BoundingBox bounds() { return previewInstance == null ? bounds : previewBounds; }
        float anchorZ() { return previewInstance == null ? decorationAnchorZ : previewAnchorZ; }
        boolean tree() { return treeAsset != null; }
        /** A tree the chunk's stand draws, at the stand's levels: no crystal, which draws as its own translucent parts. */
        boolean standTree() { return tree() && !translucent; }
        /** A route marker, rail or maglev: its track is cached with its chunk in every view and never fades. */
        boolean route() { return decorationFeature != null && BoardRail.route(decorationFeature); }
        /**
         * A tree drawn by the chunk's instanced stand. Its installed pose was built for the stand; an editor preview
         * the instance data cannot hold (a tilt or mirror) draws as an object part until the rebuild.
         */
        boolean standing() {
            return standTree() && (previewInstance == null || GpuTreeInstances.placeable(previewInstance.transform));
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
     * Marks tree impostors: a quad turned toward the camera that shows its plant's frames, whose atlases place each texel's
     * surface, so it is lit and shadowed there (impostor-sample.glsl, terrain-foliage.frag).
     */
    private static final class Impostor extends FloatAttribute {
        static final long TYPE = register("boardImpostor");

        Impostor() { super(TYPE, 0); }

        @Override
        public Impostor copy() { return new Impostor(); }
    }

    /** Whether a material is a tree impostor's (Impostor), which its passes' programs draw differently. */
    static boolean impostor(Attributes material) { return material.has(Impostor.TYPE); }

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
            if (material.id.equals(GpuAssets.MISSING) || material.id.startsWith("crystal")) { continue; }
            if (!material.has(Foliage.TYPE)) {
                boolean solid = material.id.startsWith("bark") || material.id.equals("cactus") || material.id.equals("fruit")
                      || material.id.equals("fungus") || material.id.equals("mycelium") || material.id.equals("coral");
                material.set(new Foliage(material.id.equals("fungus") || material.id.equals("mycelium") ? 3
                      : material.id.equals("snow") || material.id.equals("canopy-snow-cutout") ? 2 : solid ? 0 : 1));
            }
            // The impostor's frames are cutouts: their texels outside the plant discard in every pass, shadows included.
            // They draw as opaque (unblended, sorted with the solid trees), so they too hide the ground beneath first.
            if (material.id.equals("impostor") && !material.has(Impostor.TYPE)) {
                if (!material.has(FloatAttribute.AlphaTest)) {
                    material.set(FloatAttribute.createAlphaTest(.5f));
                }
                material.set(new BlendingAttribute(false, GL20.GL_ONE, GL20.GL_ZERO, 1), new Impostor());
            }
        }
        return model;
    }

    static boolean blended(Material material) {
        var blend = material.get(BlendingAttribute.class, BlendingAttribute.Type);
        return blend != null && blend.blended;
    }

    /**
     * A material's solid state. Alpha-tested GLB cards stay cutouts: LibGDX's shaders apply the test only under a
     * blending attribute, here with blending off. Nothing else blends or skips depth writes.
     */
    private static void opaque(Material material) {
        if (material.has(FloatAttribute.AlphaTest)) {
            material.set(new BlendingAttribute(false, GL20.GL_ONE, GL20.GL_ZERO, 1));
        } else { material.remove(BlendingAttribute.Type); }
        material.remove(DepthTestAttribute.Type);
    }

    /**
     * A cutaway placement's material faded to {@code opacity} around an occupant or hovered storey, or solid for NaN.
     * Cutouts (an authored building's plants) stay solid with their alpha test: the model shader tests the faded alpha,
     * so a blended cutout would lose its texels rather than fade. They go only with a building faded away fully, where
     * the test discards all their texels.
     */
    private static void fade(ModelInstance instance, Material material, float opacity) {
        if (Float.isNaN(opacity) || material.has(FloatAttribute.AlphaTest)) {
            opaque(material);
            culling(instance, material, false);
            if (opacity == 0) { material.get(BlendingAttribute.class, BlendingAttribute.Type).opacity = 0; }
            return;
        }
        BlendingAttribute blend = material.get(BlendingAttribute.class, BlendingAttribute.Type);
        if (blend == null) {
            blend = new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            material.set(blend);
            material.set(new DepthTestAttribute(GL20.GL_LEQUAL, false));
            culling(instance, material, true);
        }
        blend.opacity = opacity;
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

    /**
     * Whether buildings show their interiors: generated floors and struts, and walls that fade around a unit inside.
     * Off, every building is built and drawn closed (BoardScene.Cover#interiors is NO); the rules' interiors are
     * unchanged, but the pointer can no longer pick an interior floor.
     */
    static final boolean VISIBLE_BUILDING_INTERIORS = true;

    /**
     * Each surface family's floor, by ordinal: what gathers in its hollows, in place of its ground from level -1 down
     * (terrain-materials.glsl floorShare). Null keeps the ground: concrete stays paved, sand stays rippled sand and snow
     * stays snow (their level grade marks the hollow).
     */
    private static final String[] FLOORS = {
          "grassland-floor", "dirt-floor", null, "rock-floor", null, null, "lunar-floor", "fungus-floor", "desert-floor",
          "mars-floor", "volcano-floor", "tropical-floor",
    };

    private static final List<String> SCULPT_LAYERS = java.util.stream.Stream.of(
          Arrays.stream(SCULPT_MATERIALS).flatMap(Arrays::stream),
          java.util.stream.Stream.of("tundra-crust", "tundra-ground", "tundra-mat", "biome-soil",
                "tundra-ground-variant",
                "concrete-mulch", "ground-scorch", "biome-mud"),
          Arrays.stream(FLOORS).filter(floor -> SUBLEVEL_FLOORS && floor != null))
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
        /** The flags among {@link #props}: every frame animates them, and a built chunk's props do not change. */
        private List<Prop> flags;

        List<Prop> flags() {
            if (flags == null) { flags = props.stream().filter(GpuTerrain::isFlag).toList(); }
            return flags;
        }

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
        final List<GpuFungus.Emitter> spores = new ArrayList<>();
        final List<GpuGeysers.Emitter> geysers = new ArrayList<>();
        final List<GpuLavaLighting.Source> fungalLights = new ArrayList<>();
        /** Its largest tree's bounding diagonal: the shadow map's level for all its trees (renderDepth). */
        float treeDiameter;
        final List<InteriorStruts> struts = new ArrayList<>();
        final Array<Renderable> propRenderables = new Array<>();
        Array<Renderable> scatterRenderables = new Array<>();
        final Array<Renderable> shadowPropRenderables = new Array<>();
        final Array<Renderable> sharedProps = new Array<>();
        /** {@link #sharedProps} ready for the colour and depth passes; null until one next draws them. */
        GpuTreeInstances.Placements sharedPlacements;
        final Array<Renderable> sharedShadows = new Array<>();
        /** {@link #sharedShadows} ready for the shadow map (shadowTrees); null until it next draws them. */
        GpuTreeInstances.Placements shadowPlacements;
        /** Placed object parts drawn with instancing, like shared building modules (mirror images too). */
        final Array<Renderable> decorationRenderables = new Array<>();
        /** Placed object parts whose pose (tilted, sheared, unevenly stretched) or material the instance data cannot hold. */
        final Array<Renderable> looseDecorations = new Array<>();
        /** The object of each part above, in the same order: every pass asks whether it is drawn. */
        final Array<Prop> decorationOwners = new Array<>(), looseOwners = new Array<>();
        /**
         * Individual translucent primitives sort with glass and faded buildings, never with species instances; each
         * has an opaque copy for outlines and wireframe (translucentGeometry).
         */
        final Array<Renderable> translucentParts = new Array<>(), translucentSolids = new Array<>();
        final Array<Prop> translucentOwners = new Array<>();
        /** The faded texels of its props that stand in no tree stand, as fences, and whose prop each is. */
        final Array<Renderable> fadeParts = new Array<>();
        final Array<Prop> fadeOwners = new Array<>();
        final Array<Renderable> routeRenderables = new Array<>();
        List<BoardScene.Tile> editorTiles;
        /** Placed objects' props by object id, so an editor preview reads only the objects that changed. */
        final Map<String, List<Prop>> objectProps = new HashMap<>();
        /** Some placed object shows a preview pose until this chunk is replaced. */
        boolean previewed;
        /** Stale generated interiors stay hidden while their authored shell previews a different transform. */
        final Set<Coords> hiddenInteriors = new HashSet<>();
        final Set<Model> sharedInteriors = new HashSet<>();
        /**
         * The faded props, in the chunk's build order ({@link #cutaways}), set only where the fade changes. The blended
         * sort often cannot order them (libGDX's distance key, an int of 1000 x the squared camera distance, saturates and
         * ties for a distant camera), so it keeps their submission order: a stable one, not a hash set's, draws them alike
         * in every view and run.
         */
        Set<Prop> faded = Set.of();
        final RenderableProvider solidProps = (out, pool) -> supply(
              faded.isEmpty() ? shadowPropRenderables : propRenderables, out);
        final RenderableProvider shadowProps = (out, pool) -> supply(shadowPropRenderables, out);
        final RenderableProvider routes = (out, pool) -> supply(routeRenderables, out);
        final BoundingBox bounds = new BoundingBox().inf();
        /** Shore distance, depth and current of this chunk's open water; null without any. */
        GpuWaterShader.Field waterField;
        /** Lava reuses the same current/bank sampler with its own connectivity and chunk-owned texture. */
        GpuWaterShader.Field lavaField;
        /** Its roads' masks: its own atlas, or its source's where that holds them all (ChunkBuild). */
        GpuTextures<BoardScene.Pixels> roadMasks = new GpuTextures<>();

        private static void supply(Array<Renderable> source, Array<Renderable> out) {
            for (Renderable renderable : source) {
                renderable.shader = null;
                renderable.environment = null;
            }
            out.addAll(source);
        }

        /** The falls' revision its prop snapshot left out the falling structures at (GpuTerrain#refall). */
        int fallsRevision = -1;
        /**
         * Its packed props have left the levels its caches hold (GpuTerrain#packLevels); {@link #levelsChanged}: one
         * did since the last frame's check; {@link #levelsFiner}: one of them to a finer level, which shows while it
         * waits; {@link #levelsPacked}: the frame its caches last packed a level change.
         */
        boolean levelsStale, levelsChanged, levelsFiner;
        long levelsPacked = -LEVEL_WAIT - 1;
        /** The scale, pixels a world unit, its props last chose their levels at; and that of its first waiting finer level. */
        float levelScale, finerScale;

        /**
         * Whether a level change packs now: none was packed within the last {@link #LEVEL_WAIT} frames, or a waiting
         * finer level's prop has grown {@link #LEVEL_GROWTH} on screen since it asked.
         */
        boolean levelsDue(long frame) {
            return frame - levelsPacked > LEVEL_WAIT || levelsFiner && levelScale >= finerScale * LEVEL_GROWTH;
        }
        /** Its props by hex, in their order, gathered on first use (#propsAt). */
        private Map<Coords, List<Prop>> propsByHex;

        /** The props standing on a hex of a game's board, where they keep their hexes; an editor's moves them. */
        List<Prop> propsAt(Coords coords) {
            if (propsByHex == null) {
                propsByHex = new HashMap<>();
                for (Prop prop : props) {
                    propsByHex.computeIfAbsent(prop.coords(), at -> new ArrayList<>(2)).add(prop);
                }
            }
            return propsByHex.getOrDefault(coords, List.of());
        }

        /** Caches the props for drawing and shadows; the kit structures at {@code falling} hexes fall as pieces instead. */
        void cacheProps(Predicate<Coords> falling) {
            levelsStale = levelsFiner = false;
            cacheDecorations();
            if (cutaways.isEmpty() && struts.isEmpty()) { return; }
            if (shadowPropRenderables.isEmpty()) {
                cacheProps(shadowPropRenderables, false);
                // A shadow always uses the original opaque materials, independently of live instance fading.
                opaqueMaterials(shadowPropRenderables);
            }
            // These snapshots contain transforms and mesh ranges only; module geometry is never merged per building.
            List<ModelInstance> shared = new ArrayList<>();
            for (Prop prop : cutaways) {
                if (prop.building != null && !falling.test(prop.coords())) { shared.add(prop.instance); }
            }
            sharedShadows.clear();
            shadowPlacements = null;
            sharedShadows.addAll(GpuTerrainDepth.snapshot(shared));
            opaqueMaterials(sharedShadows);
            for (Prop prop : faded) { shared.remove(prop.instance); }
            for (InteriorStruts strut : struts) {
                if (sharedInteriors.contains(strut.instance.model) && interiorVisible(strut.coords)
                      && !falling.test(strut.coords)) {
                    shared.add(strut.instance);
                }
            }
            for (Prop prop : cutaways) {
                if (prop.floors && sharedInteriors.contains(prop.instance.model)
                      && interiorFloorVisible(prop) && !faded.contains(prop) && !falling.test(prop.coords())) {
                    shared.add(prop.instance);
                }
            }
            sharedProps.clear();
            sharedPlacements = null;
            sharedProps.addAll(GpuTerrainDepth.snapshot(shared));
            // Stable, opaque material keys for the instancing cache; live cutaway materials can mutate independently.
            opaqueMaterials(sharedProps);
            GpuPropBatch.disposeMeshes(propRenderables);
            // Share the complete cache for normal rendering; only occupied chunks need a second mesh cache.
            if (!faded.isEmpty()) {
                cacheProps(propRenderables, true);
            }
        }

        /**
         * Fills the plain tree stand from the chunk's standing trees at their drawn poses. New arrays, never edited in
         * place: the stand's buffers find a changed chunk by array identity, so an editor preview moves its trees.
         */
        void restand(Predicate<Matrix4> felled) {
            stand.clear();
            for (Prop prop : props) {
                // A blast's felled trees fall from their own stand (GpuDestruction).
                if (!prop.scatter && prop.standing() && !felled.test(prop.instance().transform)) {
                    stand.add(prop.treeAsset, prop.instance().transform, prop.treeDiameter);
                }
            }
        }

        /**
         * Placed objects, in every view: their parts borrow the shared asset meshes and draw instanced, so changing a
         * transform never repacks a static terrain mesh and a preview pose only changes this snapshot's transforms, which
         * each pass gathers again. Plants in the stand are drawn there. Route markers are cached with the chunk instead.
         */
        void cacheDecorations() {
            translucentParts.clear();
            translucentSolids.clear();
            translucentOwners.clear();
            fadeParts.clear();
            fadeOwners.clear();
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
                if (prop.fades && !prop.standing() && !prop.route()) {
                    for (Renderable part : GpuTerrainDepth.snapshot(List.of(prop.instance()))) {
                        if (!GpuCutoutFade.fades(part.material)) { continue; }
                        part.material = GpuCutoutFade.fadeMaterial(part.material);
                        if (GpuTreeInstances.instanceable(part.worldTransform) && instancedShader(part.material)) {
                            culling(prop.instance().model, part.material, false, false);
                        }
                        fadeParts.add(part);
                        fadeOwners.add(prop);
                    }
                }
                if (prop.decorationFeature == null && !prop.translucent || prop.standing() || prop.route()) { continue; }
                // One snapshot per object: each part keeps its object, whose size class and pin every pass tests.
                Array<Renderable> parts = GpuTerrainDepth.snapshot(List.of(prop.instance()));
                for (Renderable part : parts) {
                    if (prop.translucent && (blended(part.material) || GpuCrystalTransmission.ruby(part.material))) {
                        translucentParts.add(part);
                        translucentOwners.add(prop);
                        var solid = new Renderable().set(part);
                        var one = new Array<Renderable>();
                        one.add(solid);
                        opaqueMaterials(one);
                        translucentSolids.add(solid);
                        continue;
                    }
                    var one = new Array<Renderable>();
                    one.add(part);
                    opaqueMaterials(one);
                    boolean instanced = !isFlag(prop) && GpuTreeInstances.instanceable(part.worldTransform) && instancedShader(part.material);
                    // An instanced mirror image keeps its model's culling: its batch flips the front face instead.
                    if (instanced) { culling(prop.instance().model, part.material, false, false); }
                    (instanced ? decorationRenderables : looseDecorations).add(part);
                    (instanced ? decorationOwners : looseOwners).add(prop);
                }
            }
        }

        private boolean interiorVisible(Coords coords) {
            return faded.stream().anyMatch(prop -> !prop.floors && prop.coords.equals(coords));
        }

        /**
         * Whether a hex's floors draw: while its building is faded around an occupant, and always in a building that
         * stands open (a missing or collapsed end). A closed building draws none otherwise. Never in the shadow pass:
         * the shell casts the building's shadow.
         */
        private boolean interiorFloorVisible(Prop floor) {
            return floor.openShell || interiorVisible(floor.coords);
        }

        private static void opaqueMaterials(Array<Renderable> renderables) {
            for (Renderable renderable : renderables) {
                renderable.material = new Material(renderable.material);
                opaque(renderable.material);
                // Keep authored culling, including the reversed winding of mirrored native placements.
            }
        }

        /** Placed objects are never packed: {@link #cacheDecorations} draws them. */
        private void cacheProps(Array<Renderable> destination, boolean omitFaded) {
            GpuPropBatch.cache(destination, builder -> {
                for (InteriorStruts strut : struts) {
                    if (omitFaded && !sharedInteriors.contains(strut.instance.model)
                          && interiorVisible(strut.coords)) {
                        builder.add(strut.instance);
                    }
                }
                for (Prop prop : cutaways) {
                    if (prop.decorationFeature != null || prop.translucent) { continue; }
                    if (prop.building == null && !sharedInteriors.contains(prop.instance.model)
                          && (!prop.floors || omitFaded && interiorFloorVisible(prop))
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
            for (List<ModelInstance> layer : List.of(opaque, scatter, overlays, water, surfaceDecals, tilesetDecals, authoredPaint)) {
                layer.forEach(instance -> instance.model.dispose());
            }
            if (waterField != null) { waterField.dispose(); }
            if (lavaField != null) { lavaField.dispose(); }
            // Once: a shared atlas counts its owners.
            if (roadMasks != null) { roadMasks.dispose(); }
            roadMasks = null;
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
        // Part of every program's key: the editor keeps it, as it keeps the biome stage below, or the first crystal in a
        // stroke or a palette card links every program again. Without a crystal its programs read no transmission. A
        // board that had crystals keeps it too (boardChanged): an attack that destroys its last ones links nothing.
        if (crystalTiles != scene.tiles()) {
            crystalTiles = scene.tiles();
            crystals |= scene.tiles().stream().flatMap(tile -> tile.features().stream())
                  .anyMatch(feature -> feature.asset().startsWith("crystal/"));
        }
        environmentLighting.crystals = (crystalStage != null ? crystalStage : editable || crystals) ? crystalTransmission : null;
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
            // In play, on a board already shown, a change that follows another while its rebuild still prepares (a
            // strike's scorch, a frame after its hexes) joins it: that rebuild, with no chunk built yet, starts over
            // for both, once, instead of two rebuilds and two commits in a row.
            boolean joins = !editable && coverScene != null && rebuild != null && rebuild.plan == null
                  && rebuild.request.generation() == meshGeneration && meshGeneration != joinedGeneration;
            if (requested == null || joins || !sameBoard(target, scene) || !sameSettings(targetSettings, settings)
                  || targetColumns != columns || targetTessellated != tessellation) { meshGeneration++; }
            if (joins) { joinedGeneration = meshGeneration; }
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
            GpuBiomeSurface.mark(environment, biomeStage(scene));
            retiredChunks.addAll(detailCache.values());
            detailCache.clear();
        } else if (requested == null) {
            try (TerrainSettings.Scope ignored = TerrainSettings.use(settings)) {
                tiles = scene.tiles();
                coverScene = scene;
                GpuBiomeSurface.mark(environment, biomeStage(scene));
                installedSettings = settings;
                if (!Objects.equals(light, scene.light())) { updateLight(scene.light()); }
            }
        }
    }

    /** Whether this board's programs compile the biome stage: the warm-up's choice, else the editor's or the board's. */
    private boolean biomeStage(BoardScene scene) {
        return biomeStage != null ? biomeStage : editable || biomesActive(scene);
    }

    /** GpuBiomeSurface.active for these tiles, asked every frame of a list that changes only with the scene. */
    private boolean biomesActive(BoardScene scene) {
        if (biomeTiles != scene.tiles()) {
            biomeTiles = scene.tiles();
            biomesActive = GpuBiomeSurface.active(scene);
        }
        return biomesActive;
    }

    /**
     * The models {@code scene}'s features may draw (collectFeatures): not scatter (but fungus), boulders, rough ground,
     * shafts or decals.
     */
    private static java.util.Set<String> featureModels(BoardScene scene) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (BoardScene.Tile tile : scene.tiles()) {
            // What comes down leaves rubble: the load links its program from this pile (ChunkBuild#finish).
            if (BoardScene.TileTransition.structure(tile)) { names.add(RUBBLE_PILE); }
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
     * Whether the warm-up last prepared for this lighting configuration: then at most an edit's new materials still link,
     * and the board stays shown (ready).
     */
    private boolean sameMaterialConfiguration() {
        return terrainMaterialEnvironment == environment.getMask()
              && terrainMaterialFlags == environmentLighting.flags()
              && terrainMaterialShadows == (environment.shadowMap != null);
    }

    /**
     * An edit's rebuild is complete but some program its new materials need still links, or a structure it removes is
     * still being cut into the pieces that take its place as it commits: it waits to commit.
     */
    private boolean awaitingLinks() {
        return rebuild != null && rebuild.plan != null && rebuild.remaining.isEmpty()
              && (!familySession && !materialsReady() || cutting());
    }

    /** An edit still builds or waits for its programs: the board shows the terrain from before it. */
    boolean editPending() { return requested != null || rebuild != null; }

    boolean busy() {
        return requested != null || rebuild != null || detailJob != null || !pending.isEmpty()
              || coverScene != null && !materialsReady();
    }

    /** The loading stage (ready, buildProgress) waits for a session's family programs; busy does not, nor tests. */
    private void startFamilySession() { familySession = true; }

    /** Whether the load's family programs still build: the loading stage waits for them (ready), busy does not. */
    boolean familiesPending() { return familySession && familyPrograms.pending() > 0; }

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
        clearCollapses();
        coverScene = null;
        crystalTiles = null;
        crystals = false;
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

    /** Ages Ground Zero's glow by these blast seconds (GpuArtilleryBlast#advance). */
    void advanceScorch(float seconds) { groundDamage.advance(seconds); }

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

    /** Live A/B switch for the ground material cache, retaining its allocation. */
    void setMaterialCache(boolean enabled) { materialCache.setEnabled(enabled); }

    void setMaterialCacheDebug(boolean enabled) { materialCache.setDebug(enabled); }

    GpuMaterialCache.Status materialCacheStatus() { return materialCache.status(); }

    boolean materialCacheComplete() { return materialCache.complete(); }

    String materialCacheReport() { return materialCache.report(); }

    /**
     * Whether combat scars show. On before the board builds (games), it also prepares the plain programs of the objects
     * drawn instanced, with which their scar patches draw (GpuSurfaceScars); a patch on the terrain draws with its
     * receiver's sculpt program. Off where combat cannot happen (the editor, previews).
     */
    void setCombatScars(boolean scars) { combatScars = scars; }

    /**
     * Whether its kit structures break into fragments ahead of their falls (KitFragments): in a game's own view, never
     * in the editor, a preview or the shader warm-up, where nothing falls.
     */
    void setFragments(boolean breaks) { fragments = breaks; }

    /** Before the board builds: whether the editor changes it (editable). */
    void setEditable(boolean editing) { editable = editing; }

    /**
     * The shader warm-up (GpuShaderWarmup) compiles one sample board with the biome stage and crystal transmission or
     * without them, whatever the board holds, for each lighting configuration a game can have. Null follows the board.
     */
    void setLightingStages(Boolean biomes, Boolean crystals) {
        biomeStage = biomes;
        crystalStage = crystals;
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
        // The recent road masks' keys tell them apart within one set of settings, not the settings a full rebuild may bring.
        if (rebuildAll) { roadMaskData.forget(); }
        boolean changedFlow = rebuildAll;
        Map<Coords, BoardFlow.Current> nextCurrents = previousCurrents;
        // The fitted concrete outline reads ground, water, levels, roads and features. An edit that changes none
        // of them keeps the previous fit, and the corner comparison below then has nothing to find.
        boolean refit = rebuildAll || previousCoast == null || concreteInputsChanged(beforeScene, scene);
        BoardConcrete nextCoast = refit ? BoardConcrete.of(scene) : BoardConcrete.adopt(scene, previousCoast);
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
                          || before.road() != tile.road() || BoardRoad.singleLane(before) != BoardRoad.singleLane(tile)
                          || before.cliffTopExits() != tile.cliffTopExits() || before.ultraSublevel() != tile.ultraSublevel()
                          || before.surface() != tile.surface() || before.detailedGround() != tile.detailedGround()
                          || before.backdrop() != tile.backdrop())
                          || refit && !nextCoast.sameCorners(previousCoast, tile.coords());
                    if (shore || !same && (before.frozen() != tile.frozen() || before.biome() != tile.biome()
                          || !before.groundCover().equals(tile.groundCover())
                          || BoardWall.family(before) != BoardWall.family(tile)
                          // A structure's exits pick its own and its neighbours' connection pieces (ConnectedKit#reach).
                          || before.cover().exits() != tile.cover().exits()
                          // A single lane's direction turns its own arrows and its neighbours' ramp markings.
                          || !Objects.equals(megamek.common.board.HexAppearance.inbound(before.appearance(), "road"),
                                megamek.common.board.HexAppearance.inbound(tile.appearance(), "road"))
                          || !Objects.equals(megamek.common.board.HexAppearance.exitFrom(before.appearance(), "road"),
                                megamek.common.board.HexAppearance.exitFrom(tile.appearance(), "road"))
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

    /** Whether any hex changed what the concrete fit reads: its ground, water, level, roads, structures, Edges or sides. */
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
                  || BoardConcrete.edges(was) != BoardConcrete.edges(is)
                  || was.pavementSides() != is.pavementSides()) { return true; }
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
        // Smooth steps move their corners on a field like the shore's, read from the levels round them.
        if (BoardRelief.tuning().stepOutline() == BoardRelief.StepOutline.SMOOTH
              && before.tile(at).groundLevel() != after.tile(at).groundLevel()) { return BoardSurface.SHORE_RINGS; }
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
        // A requested board's update plan starts before links, which can hold a first frame for seconds; links wait
        // until its first sections start, so these prepare on the workers while they link.
        if (requested != null && rebuild == null && detailJob == null && pending.isEmpty()) { startRebuild(); }
        boolean starting = rebuild != null && rebuild.started.isEmpty()
              && (rebuild.plan == null || !rebuild.remaining.isEmpty());
        // Launch links while CPU terrain jobs are being prepared, and poll without blocking the loading screen.
        if (!materialsReady() && !starting) {
            if (terrainMaterialPreparation == null && requested != null) { updateLight(requested.scene().light()); }
            prepareTerrainMaterials();
        }
        // Family programs start behind the loading stage, after the generic ones: on Iris Xe a link in this process beside
        // interactive frames once held one frame for 9 s. The link helper's hold none, so those go on (GpuFamilyPrograms).
        if (familyPrograms.configure(environment)) {
            terrainBatch.clear();
            startFamilySession();
        }
        boolean session = buildingFamilies();
        if (session || familyPrograms.adopting()) {
            if (familyPrograms.prepare(terrainMaterialPreparation, batch.getShaderProvider(), environment)) {
                terrainBatch.clear();
            }
            if (session) { familySession = familyPrograms.pending() > 0; }
        }
        // Once geometry and programs are ready, give the material cache a bounded head start. Interactive frames
        // continue baking it; complete direct terrain can already display the map.
        if (requested == null && rebuild == null && materialsReady()) {
            // Its fill reads the fields' biomes (cachedField).
            biomes.update(coverScene);
            materialCache.prewarm(camera, !installedColumns, this::drawCacheTops,
                  () -> cacheChunks(camera));
        }
        // While opening/replacing a board or switching the graphics (both behind the loading stage), use more of the
        // loading frame for uploads instead of stretching each chunk's handoffs over several frames: a new board's
        // frames, which nothing else draws in and no monitor refresh holds (GpuBattleView#pace), the most. An editor change
        // gets a larger bounded slice so each small edit does not wait dozens of frames (its collection and upload take
        // about 60 ms of render-thread time, a handful of frames at this slice); ordinary camera refinement keeps the
        // two-millisecond budget.
        long deadline = System.nanoTime() + (coverScene == null ? 16_000_000
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
            } else {
                job.surfaces().thenAccept(Prepared::discard);
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

    /** Frees what a job the terrain closes with holds: its chunk under construction, or what its workers prepare for one. */
    private static void discard(DetailJob job) {
        if (job.build() != null) { job.build().chunk.dispose(); }
        else { job.surfaces().thenAccept(Prepared::discard); }
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
              || settings.call(() -> new GpuMaterialCache.Charts(scene, x, y, bottom).sameLayout(source.charts)))) {
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
                          () -> checkBuild(generation, stale), progress, roadMaskData, paint, paintSurfaces).packed()), detailWorker),
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
              infrastructure.bridges(), infrastructure.bridgeShapes(), Map.of(), reused, paint, false, infrastructure.authored(),
              Map.of());
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
              Map.of(), Map.of(), Map.of(), Set.of(), paint, true, Map.of(), Map.of());
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
        // The structures this commit removes break into their pieces in the very frame they go.
        startCollapses();
        destruction.committed(at -> {
            var tile = coverScene.tile(at);
            return tile != null && BoardScene.TileTransition.structure(tile);
        }, (at, trees) -> {
            var tile = coverScene.tile(at);
            return tile != null && tile.features().stream().anyMatch(trees::contains);
        });
        takeRubble();
        // A chunk finished before structures came down (or were restored) draws them as they are now.
        for (Chunk chunk : chunks) {
            if (chunk.fallsRevision != destruction.revision()) { refall(chunk); }
        }
        cutFragments();
        editorPropsInstalled = true;
        flagScene++;
        lavaLighting.setFungus(chunks.stream().flatMap(chunk -> chunk.fungalLights.stream()).toList());
        updateBuildingLights();
        installedSettings = rebuild.request.settings();
        if (installedColumns != rebuild.request.columns() && rebuild.request.columns()) {
            // The 3D relief's board-wide layers, once, on a switch to Classic; the ocean follows in animate. The
            // per-hex biome texture stays: Classic's ice reads it.
            groundCover.dispose();
            biomeVegetation.dispose();
        }
        installedColumns = rebuild.request.columns();
        installedTessellation = rebuild.request.tessellated();
        // The startup camera no longer builds hidden full-detail chunks just to discover the grass program.
        if (!installedColumns && scene.tiles().stream().anyMatch(tile ->
              BoardBiome.kind(tile) == BoardScene.Biome.FIELD || GpuGroundCover.grows(scene, tile))) {
            GpuGroundCover.prepareMaterials(this::prepareTerrainMaterial);
        }
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
        // By identity, each assembly once: an assembly's own hash reads its every placement, and a city's props name
        // the same few hundred assemblies thousands of times.
        Set<GpuBuilding.Assembly> liveBuildings = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        // Generated models the installed chunks draw: industrial plants, and multi-hex building shares and their glass.
        Set<Model> liveIndustrial = new HashSet<>();
        Set<Model> liveRoutes = new HashSet<>();
        Set<Mesh> sharedSources = new HashSet<>();
        List<Chunk> kept = new ArrayList<>(chunks);
        kept.addAll(detailCache.values());
        for (Chunk chunk : kept) {
            for (Prop prop : chunk.props) {
                if (isFlag(prop) || prop.decorationFeature != null && BoardRail.route(prop.decorationFeature)) {
                    liveRoutes.add(prop.instance.model);
                }
                if (prop.building != null) { liveBuildings.add(prop.building); }
                if (!prop.tree()) { liveIndustrial.add(prop.instance.model); }
            }
            for (ModelInstance pane : chunk.glass) { liveIndustrial.add(pane.model); }
            for (Renderable part : chunk.sharedShadows) { sharedSources.add(part.meshPart.mesh); }
            for (Renderable part : chunk.sharedProps) { sharedSources.add(part.meshPart.mesh); }
            for (Renderable part : chunk.decorationRenderables) { sharedSources.add(part.meshPart.mesh); }
        }
        // Falling pieces borrow their kits' meshes until they have sunk.
        liveBuildings.addAll(destruction.buildings());
        trees.retainParts(sharedSources);
        shadowTrees.retainParts(sharedSources);
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
                  && sameMaterialVariant(material, GpuIceShader.variant(sample.material), linearDiffuse(sample.material),
                        GpuSurfaceBlend.ordinaryFamily(sample.material), waterMode(sample.material),
                        flowingMagma(sample.material), GpuCrystalTransmission.ruby(sample.material))
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

    /** The programs of a chunk's placed objects at their drawn levels: instanced parts as their batches draw them. */
    private void prepareDecorations(Chunk chunk) {
        for (Array<Renderable> layer : List.of(chunk.looseDecorations, chunk.translucentParts)) {
            for (Renderable part : layer) { prepareTerrainMaterial(part.material, part.meshPart.mesh.getVertexAttributes()); }
        }
        for (Renderable part : chunk.decorationRenderables) {
            prepareTerrainMaterial(GpuTreeInstances.instancedMaterial(part.material), part.meshPart.mesh.getVertexAttributes());
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
            programs += (twinned ? 2 : 1) + (cacheable ? 2 : 0);
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
            boolean cachedLit = colour && cacheable && terrainMaterialPreparation.run(() -> cacheProgram(sample,
                  GpuMaterialCache.cachedTwin(sample.material), sample.environment, batch.getShaderProvider()));
            boolean fills = colour && cacheable && terrainMaterialPreparation.run(() -> cacheProgram(sample,
                  materialCache.fillMaterial(sample.material), null, fillShaders()));
            GpuGlsl.spent(System.nanoTime() - start);
            linked += (colour ? 1 : 0) + (twin ? 1 : 0) + (cachedLit ? 1 : 0) + (fills ? 1 : 0);
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
        flagScene++;
        Chunk old = chunks.set(index, next);
        // A level kept while structures came down or were restored draws them as they are now.
        if (next.fallsRevision != destruction.revision()) { refall(next); }
        levelsPending |= next.levelsStale;
        // The kit levels a chunk at another detail level draws break ahead of their falls too (KitFragments).
        cutFragments(next);
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
              infrastructure.bridges(), infrastructure.bridgeShapes(), walls, reused, paint, false, infrastructure.authored(),
              plantSupports(scene, chunkTiles, lod, surfaces, reused, settings, check));
    }

    /**
     * Natural plants stand on full-detail support whatever the chunk's level, so zooming moves none of them and rerolls
     * no colony's collision retries. Made here so that the render thread's collection only reads it. A full-detail
     * chunk's finished surfaces answer those queries identically and are shared.
     */
    private static Map<Coords, BoardSurface> plantSupports(BoardScene scene, List<BoardScene.Tile> chunkTiles, TerrainLod lod,
          Map<Coords, BoardSurface> surfaces, Set<Coords> reused, TerrainSettings settings, Runnable check) {
        Map<Coords, BoardScene.Tile> needed = new HashMap<>();
        for (var tile : chunkTiles) {
            for (var feature : tile.features()) {
                if (!naturalPlant(feature)) { continue; }
                needed.put(tile.coords(), tile);
                // A coral's wide roots rest on the hexes around it as well.
                if (feature.asset().startsWith("mars/")) {
                    for (int direction = 0; direction < 6; direction++) {
                        var next = scene.tile(tile.coords().translated(direction));
                        if (next != null) { needed.put(next.coords(), next); }
                    }
                }
            }
        }
        Map<Coords, BoardSurface> result = new java.util.concurrent.ConcurrentHashMap<>();
        needed.values().parallelStream().forEach(tile -> settings.run(() -> {
            check.run();
            var own = lod == TerrainLod.FULL && !reused.contains(tile.coords()) ? surfaces.get(tile.coords()) : null;
            result.put(tile.coords(), own != null ? own : new BoardSurface(scene, tile));
        }));
        return result;
    }

    private static boolean naturalPlant(BoardScene.Feature feature) {
        return feature.kind() == BoardScene.FeatureKind.TREE && !feature.authoredPlacement()
              || feature.kind() == BoardScene.FeatureKind.SCATTER && BoardFungus.asset(feature.asset());
    }

    /**
     * The roads painting a hex's ground: those nearby at its level ({@link BoardRoad#nearby}) and, in Enhanced, a
     * neighbour's at another level whose carriageway reserves part of this hex.
     */
    private static List<BoardRoad> painting(BoardScene scene, BoardScene.Tile tile, boolean columns) {
        // Classic's water column shows its liquid only.
        if (columns && tile.liquid().present()) { return List.of(); }
        var result = new ArrayList<>(columns ? BoardRoad.nearbyDry(scene, tile) : BoardRoad.nearby(scene, tile));
        for (int d = 0; d < 6 && !columns; d++) {
            var source = scene.neighbor(tile, d);
            if (source != null && source.floor() != tile.floor() && BoardRoad.rendered(source)
                  && BoardRoadGrade.reaches(scene, source, tile)) { result.add(BoardRoad.of(scene, source)); }
        }
        return result;
    }

    /** Shared carriageways, paint and rail supports; Classic natural decks use a full hex cap. */
    private record Infrastructure(Map<Coords, List<RoadPatch>> roads, Map<Coords, BoardBridge.Deck> bridges,
          Map<Coords, BoardBridge.Shape> bridgeShapes, Map<GpuAuthoredBuilding.Layout, GpuAuthoredBuilding.Prepared> authored) { }

    /**
     * A road's paint where it lies on its ground, with the key of each coat's mask: the same for every hex it reaches, so a
     * chunk builds it once. Those hexes' workers then only read it.
     */
    private record RoadPaint(BoardRoad road, List<RoadCoat> coats) { }

    private record RoadCoat(GpuRoads.Patch patch, int density, Object maskKey) { }

    private static RoadPaint roadPaint(BoardScene scene, BoardScene.Tile source, BoardRoad shared, TerrainLod lod, boolean columns) {
        // A road ending on a cliff's top wears out to gravel at the rim (Enhanced).
        BoardRoad road = columns ? shared : GpuRoads.painted(scene, source, shared);
        List<RoadCoat> coats = new ArrayList<>();
        for (var patch : GpuRoads.patches(scene, source, road, shared)) {
            road.clipPaint(patch.shape());
            // An Area caches its bounds when first asked: settled here, before other workers read it.
            patch.shape().getBounds2D();
            // Distant wear fields need fewer samples; paint and closer levels retain their original density.
            int density = columns ? 4 : GpuRoads.maskDensity(patch, lod);
            coats.add(new RoadCoat(patch, density, GpuRoads.maskKey(road, patch, density)));
        }
        return new RoadPaint(road, coats);
    }

    private static Infrastructure prepareInfrastructure(BoardScene scene, List<BoardScene.Tile> chunkTiles,
          TerrainLod lod, TerrainSettings settings, Map<Coords, BoardSurface> surfaces, Runnable check,
          TerrainLoadProgress progress, GpuRoads.Masks masks, boolean columns) {
        Map<Coords, List<RoadPatch>> roads = new java.util.concurrent.ConcurrentHashMap<>();
        Map<Coords, BoardBridge.Deck> bridges = new java.util.concurrent.ConcurrentHashMap<>();
        Map<Coords, BoardBridge.Shape> bridgeShapes = new java.util.concurrent.ConcurrentHashMap<>();
        Map<Coords, RoadPaint> paints = new java.util.concurrent.ConcurrentHashMap<>(2 * chunkTiles.size());
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
                var paint = paints.computeIfAbsent(source.coords(), at -> roadPaint(scene, source, shared, lod, columns));
                for (var coat : paint.coats()) {
                    check.run();
                    var patch = coat.patch();
                    var triangles = GpuRoads.drape(source, surface, patch);
                    if (!triangles.isEmpty() && !patch.shape().isEmpty()) {
                        var mask = masks.mask(coat.maskKey(), () -> GpuRoads.mask(paint.road(), patch, coat.density()), check);
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
                // A fallen rock arch leaves nothing standing.
                if (feature.ruined() && deck.natural()) { continue; }
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
                // A fallen span's road stays painted on its landings, which still stand (builtShape).
                var shape = builtShape(scene, tile, deck, footing, lod, surfaces, columns);
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
                        // A fallen rock arch carries nothing; computeIfAbsent keeps no null.
                        if (deck.natural()) {
                            return BoardBridge.feature(next).ruined() ? null
                                  : BoardNaturalBridge.build(scene, next, deck, lod, surfaces, columns);
                        }
                        var shape = builtShape(scene, next, deck, BoardBridgeFooting.build(scene, next, lod, surfaces, columns),
                              lod, surfaces, columns);
                        return shape.facets().isEmpty() ? null : shape;
                    });
                }
            }
        }
        return new Infrastructure(roads, bridges, bridgeShapes, GpuAuthoredBuilding.prepare(scene, chunkTiles));
    }

    /**
     * A built bridge's shape on its {@code footing}: its deck with its piers, or once its span fell what still stands of
     * it, its landings and the piers under its joints ({@link BoardBridgeFooting#ruin}).
     */
    private static BoardBridge.Shape builtShape(BoardScene scene, BoardScene.Tile tile, BoardBridge.Deck deck,
          BoardBridgeFooting footing, TerrainLod lod, Map<Coords, BoardSurface> surfaces, boolean columns) {
        return BoardBridge.feature(tile).ruined() ? footing.ruin(scene, tile, deck, lod, surfaces, columns)
              : footing.withDeck(scene, tile, deck, lod, surfaces, columns);
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
                Material frozenFall = GpuIceShader.waterfall(material(rainNoise, true));
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
                List<Model> levels = assets.levels(placement.asset());
                levels.forEach(GpuTerrain::foliage);
                ModelInstance instance = new ModelInstance(levels.getFirst());
                instance.transform.set(placement.transform());
                BoundingBox bounds = instance.calculateBoundingBox(new BoundingBox()).mul(instance.transform);
                var prop = new Prop(tile.coords(), instance, levels, bounds, placement.asset(), false);
                prop.scatter = true;
                chunk.props.add(prop);
                chunk.bounds.ext(bounds);
            }
        }
        for (BoardRough.Placement placement : surface.roughModels()) {
            List<Model> levels = assets.levels(placement.asset());
            ModelInstance instance = new ModelInstance(levels.getFirst());
            instance.transform.set(placement.transform());
            BoundingBox bounds = instance.calculateBoundingBox(new BoundingBox()).mul(instance.transform);
            var rough = new Prop(tile.coords(), instance, levels, bounds, null, false);
            chunk.props.add(rough);
            chunk.bounds.ext(bounds);
        }
        collectFeatures(build, tile, surface, new GroundView(surface.faces, BoardGeometry.groundZ(tile),
              prepared.bridgeShapes().get(tile.coords())));
    }

    /** Portals belong to their road in every graphics mode. */
    private void collectTunnels(ChunkBuild build, BoardScene.Tile tile) {
        for (var tunnel : BoardTunnel.entrances(build.scene, tile)) {
            List<Model> levels = assets.levels(tunnel.asset());
            ModelInstance instance = new ModelInstance(levels.getFirst());
            // The road continues inside with the same world UVs, normal/surface maps and weather response as its approach.
            Material tunnelFloor = instance.getMaterial("tunnel-floor");
            String road = GpuRoads.texture(tunnel.kind());
            tunnelFloor.set(roadMaterial(road.substring("roads/".length()), false));
            tunnelFloor.set(new BridgeDeck(assets.roadCoverage(road)));
            instance.transform.set(tunnel.transform());
            BoundingBox bounds = instance.calculateBoundingBox(new BoundingBox()).mul(instance.transform);
            var portal = new Prop(tile.coords(), instance, levels, bounds, null, false);
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
     * A building hex's interior beside its shell: its struts, and its floors as one prop, since a hex shows all of
     * them or none (Chunk#interiorFloorVisible).
     */
    private static void interior(Prop shell, Model interior, boolean open, List<Prop> props, List<InteriorStruts> struts) {
        ModelInstance columns = new ModelInstance(interior, "struts");
        columns.transform.set(shell.instance.transform);
        if (columns.nodes.first().parts.notEmpty()) { struts.add(new InteriorStruts(shell.coords, columns)); }
        ModelInstance floors = new ModelInstance(interior, "floors");
        var parts = floors.nodes.first().parts;
        for (int part = parts.size - 1; part >= 0; part--) {
            if (parts.get(part).meshPart.size == 0) { parts.removeIndex(part); }
        }
        if (parts.isEmpty()) { return; }
        floors.transform.set(shell.instance.transform);
        // ModelBuilder recorded each part's exact box (MeshBuilder#endpart) under the floors node's identity frame: no
        // vertex scan per placed interior.
        BoundingBox bounds = new BoundingBox().inf();
        for (var part : parts) {
            bounds.ext(new BoundingBox(new Vector3(part.meshPart.center).sub(part.meshPart.halfExtents),
                  new Vector3(part.meshPart.center).add(part.meshPart.halfExtents)));
        }
        bounds.mul(floors.transform);
        Prop prop = new Prop(shell.coords, floors, List.of(floors.model), bounds, null, false, null, true, true, false);
        prop.infrastructure = shell.infrastructure;
        prop.openShell = open;
        props.add(prop);
    }

    /**
     * Builds a closed kit building's interior, now that a unit stands in its hex ({@link Prop#interiorPending}): after
     * its shell in the chunk's order, as a building that stands open has it, so that both blend alike; at the end of
     * its hex's props, whose places scars name.
     */
    private static void showInterior(Chunk chunk, Prop shell) {
        shell.interiorPending = false;
        Model interior = shell.building.interior();
        List<Prop> props = new ArrayList<>(1);
        List<InteriorStruts> struts = new ArrayList<>(1);
        interior(shell, interior, false, props, struts);
        chunk.sharedInteriors.add(interior);
        chunk.props.addAll(chunk.props.indexOf(shell) + 1, props);
        chunk.cutaways.addAll(chunk.cutaways.indexOf(shell) + 1, props);
        chunk.struts.addAll(struts);
        TileMesh tile = chunk.tileMeshes.get(shell.coords);
        tile.props.addAll(props);
        tile.struts.addAll(struts);
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
        List<BoardPlantFootprint> colonies = new ArrayList<>();
        var colonyShapes = new HashMap<BoardPlantFootprint.Key, BoardPlantFootprint>();
        var colonyWays = features.stream().anyMatch(f -> BoardFeatures.rigid(f.asset())) ? BoardRoad.nearbyWays(scene, tile) : List.<BoardRoad>of();
        for (BoardScene.Feature feature : features) {
            if (build.preview != null && !routeFeature(tile, feature)) { continue; }
            if (feature.decoration() != null && feature.decoration().kind().equals("decal")) { continue; }
            if (build.chunk.columns && (!columnFeature(feature) || BoardFeatures.submergedControl(tile, feature))) {
                continue;
            }
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
                    // A fallen rock arch leaves nothing.
                    if (!feature.ruined()) {
                        // A cap has art only where a bank of the span's surface abuts it; else the arch shows its rock.
                        TextureRegion cap = chunk.columns && GpuTilesetTerrain.naturalBridgeArt(scene, tile, deck) != null
                              ? this.ground.region(new GroundSlot(tile.coords(), true)) : null;
                        naturalBridge(solid, bridgeShape, tile.deckMud(), cap != null,
                              !chunk.columns && BoardNaturalBridge.overhang(scene, tile));
                        if (cap != null) { GpuTilesetTerrain.naturalBridgeTop(tile, bridgeShape, cap, solid::add); }
                    }
                } else if (bridgeShape != null) { builtBridge(solid, bridgeShape, deck, scene, tile); }
                solid.route = false;
                continue;
            }
            var authoredLayout = GpuAuthoredBuilding.supports(feature) ? GpuAuthoredBuilding.layout(scene, tile, feature) : null;
            var authoredPrepared = authoredLayout == null ? null : build.prepared.authored().get(authoredLayout);
            if (authoredPrepared != null && authoredPrepared.levels().getFirst().meshes.isEmpty()) { continue; }
            var authored = authoredPrepared == null ? null : assets.authoredBuilding(authoredPrepared);
            boolean generatedIndustrial = BoardIndustrial.supports(feature);
            boolean wallBuilding = BoardWall.building(tile.cover(), feature);
            var wallLayout = BoardWall.supports(feature.asset()) ? BoardWall.layout(scene, tile, feature) : null;
            var multiHexLayout = BoardMultiHexBuilding.supports(feature.asset()) ? BoardMultiHexBuilding.layout(scene, tile, feature) : null;
            GpuBuilding.Assembly building = generatedIndustrial || feature.decoration() != null ? null : assets.building(feature.asset(), Math.round(feature.height()),
                  ConnectedKit.reach(scene, tile, Math.round(feature.height())), GpuBuilding.seed(tile, feature),
                  feature.kind() == BoardScene.FeatureKind.BUILDING && !wallBuilding ? tile.cover().interiors() : BuildingInteriors.NO,
                  HexAppearance.steppedBuilding(tile.appearance()), feature.footprintScale(), ConnectedKit.corners(scene, tile),
                  feature.kind() == BoardScene.FeatureKind.BUILDING ? KitDoors.cuts(scene, tile, feature) : List.of());
            // A placed object draws the same way in every view: one shared mesh per model and level (GpuAssets#slotted),
            // its colours passed per placement, its parts instanced (Chunk#cacheDecorations). Rail and maglev routes keep
            // their own route caching. What a rail owns (its buffer stops) is drawn as an object but never edited as one.
            boolean placedObject = feature.decoration() != null && authored == null;
            boolean editable = (placedObject || authored != null) && !BoardFeatures.terrainOwned(feature.decoration());
            // What it draws, as its mesh per level, near mesh first: the one place a feature's model is chosen, so no
            // kind draws a model file without the file's simpler levels (Prop#detail). A generated mesh has one level, a
            // kit assembly its kit's. Both graphics modes use the same finished rail carrier, including road embedding
            // and bridge grades.
            List<Model> levels = authored != null ? authored.levels()
                  : BoardRail.route(feature) ? List.of(routeModel(build, BoardRail.layout(scene, tile, feature, surface,
                        prepared.bridgeShapes()::get)))
                  : placedObject ? (BoardFlag.ASSET.equals(feature.asset())
                        ? List.of(assets.flag(feature.decoration()))
                        : BoardBillboard.ASSET.equals(feature.asset()) ? assets.billboardLevels(feature.decoration().billboard(),
                              BoardBillboardMesh.legs(feature.decoration(), tile.elevation()))
                        : assets.objectLevels(feature.asset()))
                  : generatedIndustrial ? List.of(assets.industrial(BoardIndustrial.layout(scene, tile, feature)))
                  : wallLayout != null ? assets.wallLevels(wallLayout)
                  : multiHexLayout != null ? assets.multiHexLevels(multiHexLayout)
                  : building != null ? List.of(building.model(0))
                  : feature.kind() == BoardScene.FeatureKind.TREE ? assets.levels(feature.asset())
                  : assets.levels(feature.asset(), BoardGroundCut.colours(tile, feature), BoardFeatures.label(tile, feature));
            Model model = levels.getFirst();
            assets.take(build.preview == null ? this : build.preview, building, model);
            if (authored != null) { assets.take(build.preview == null ? this : build.preview, null, authored.interior()); }
            if (building == null) {
                for (Model level : levels) {
                    if (feature.kind() == BoardScene.FeatureKind.TREE || fungus || foliageModel(level)) { foliage(level); }
                }
            }
            ModelInstance instance = building != null ? building.instance(0) : new ModelInstance(model);
            if (feature.kind() == BoardScene.FeatureKind.BUILDING || authored != null) {
                GpuBuilding.tint(instance, feature.colours());
            }
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
                groundScenery(material);
            }
            // Ordinary placements copy the same unchanged model nodes: copy the model's local box (GpuAssets#bounds)
            // before transforming it; an assembled building's, once per assembly. A flag's cloth moves its vertices.
            BoundingBox bounds = building != null ? building.bounds(instance)
                  : BoardFlag.ASSET.equals(feature.asset()) ? instance.calculateBoundingBox(new BoundingBox())
                  : new BoundingBox(assets.bounds(model, instance));
            BoundingBox editorBounds = feature.decoration() == null ? null : new BoundingBox(bounds);
            if (BoardFlag.ASSET.equals(feature.asset())) {
                editorBounds.set(Vector3.Zero, new Vector3(GpuFlag.WIDTH, 0, GpuFlag.HEIGHT));
            }
            if (BoardBillboard.ASSET.equals(feature.asset())) {
                // The footings run on below the support; the outline stops at it.
                editorBounds.min.z = Math.max(editorBounds.min.z, -BoardBillboardMesh.legs(feature.decoration(), tile.elevation()));
                editorBounds.set(editorBounds.min, editorBounds.max);
            }
            boolean coral = feature.asset().startsWith("mars/");
            // Coral colonies have wide, uneven mineral roots. Embed that root layer while preserving cover height.
            float rootInset = coral ? bounds.getDepth() * .06f : 0;
            float coralScale = coral ? feature.height() * BoardGeometry.level() / (bounds.getDepth() - rootInset) : 0;
            boolean crystal = feature.asset().startsWith("crystal/");
            float plantingRadius = coral ? build.coralRootRadii.computeIfAbsent(model,
                  key -> coralRootRadius(key, bounds.min.z + rootInset)) * coralScale
                        * (float) Math.max(feature.stretch().x(), feature.stretch().y()) : BoardRelief.metres(.8f);
            boolean packedColony = feature.kind() == BoardScene.FeatureKind.TREE && !feature.authoredPlacement()
                  && feature.decoration() == null && BoardFeatures.rigid(feature.asset());
            var colony = packedColony ? BoardPlantFootprint.of(feature, colonyShapes) : null;
            boolean naturalPlant = naturalPlant(feature);
            // Plant anchors follow terrain edits, never the camera-dependent tessellation of a rendered chunk.
            var plantingSurface = naturalPlant ? build.plantSupport(tile) : surface;
            float unit = BoardGeometry.hexScale(), cx = BoardGeometry.centerX(tile.coords()), cy = BoardGeometry.centerY(tile.coords());
            float px = 0, py = 0, base = 0;
            boolean planted = false;
            for (int attempt = 0; attempt < (packedColony ? 129 : 1); attempt++) {
                px = cx + feature.x() * unit;
                py = cy + feature.y() * unit;
                if (attempt > 0) {
                    float reach = 32 * unit * (float) Math.sqrt(attempt / 128f);
                    double angle = Math.toRadians(feature.rotation()) + attempt * 2.399963;
                    px += reach * (float) Math.cos(angle); py += reach * (float) Math.sin(angle);
                }
                if (naturalPlant) {
                    // A tree stands on its hex's own ground, never over a receding rim or a transition's slope.
                    // Roadside cover keeps its captured route clearance instead of being pulled toward the carriageway.
                    float[] spot = plantingSurface.relief.settle(px, py, crystal ? 0 :
                          coral && tile.road() != BoardRoad.Kind.NONE ? BoardRelief.metres(.8f) : plantingRadius);
                    px = spot[0]; py = spot[1];
                }
                base = naturalPlant ? plantingSurface.height(px, py) : ground.height(px, py);
                if (crystal && naturalPlant) {
                    var roots = build.crystalRoots.computeIfAbsent(model,
                          key -> crystalRoots(key, bounds.min.z + bounds.getDepth() * .04f));
                    float scale = BoardTreeCoverage.scale(feature, bounds.getDepth()) * unit;
                    float cosine = (float) Math.cos(Math.toRadians(feature.rotation()));
                    float sine = (float) Math.sin(Math.toRadians(feature.rotation()));
                    boolean supported = true;
                    for (Vector3 root : roots) {
                        float x = root.x * scale * (float) feature.stretch().x();
                        float y = root.y * scale * (float) feature.stretch().y();
                        float rx = px + cosine * x - sine * y, ry = py + sine * x + cosine * y;
                        if (plantingSurface.relief.clearance(rx, ry) < -.001f) { supported = false; break; }
                        base = Math.min(base, plantingSurface.height(rx, ry));
                    }
                    if (!supported) { continue; }
                }
                if (coral) {
                    // Sample the drawn terrain around the roots; a high point must not suspend the rest of the base.
                    for (int sample = 0; sample < 8; sample++) {
                        double angle = sample * Math.PI / 4;
                        float x = px + plantingRadius * (float) Math.cos(angle);
                        float y = py + plantingRadius * (float) Math.sin(angle);
                        var receiving = BoardGeometry.tile(scene, x, y);
                        var support = receiving == null ? plantingSurface : naturalPlant ? build.plantSupport(receiving)
                              : surfaces.getOrDefault(receiving.coords(), surface);
                        base = Math.min(base, support.height(x, y));
                    }
                    base -= (bounds.min.z + rootInset) * coralScale;
                }
                if (packedColony) {
                    // Cliff support can pull separately packed anchors together. Recheck the actual drawn XYZ,
                    // trying nearby supported positions without changing species, dimensions or earlier members.
                    float z = base + (coral ? (bounds.min.z + rootInset) * coralScale : 0)
                          - tile.elevation() * BoardGeometry.level();
                    var body = colony.at((px - cx) / unit, (py - cy) / unit, z / unit);
                    if (!body.inside() || BoardRoad.distance(colonyWays, px, py) < BoardRoad.SHOULDER + 2
                          || colonies.stream().anyMatch(body::overlaps)) { continue; }
                    colonies.add(body);
                }
                planted = true;
                break;
            }
            if (!planted) { continue; }
            if (authored != null) {
                instance.transform.setToTranslation(cx, cy, 0).scale(unit, unit, unit);
            } else if (BoardRail.route(feature)) {
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
                // As import's receiver: on a bridge hex everything but trees stands on the deck; under a fallen span, as
                // its rubble, on the floor its pieces fell to. An earthwork stands on its hex's ground as it was before it
                // was dug.
                boolean onDeck = BoardBridge.standing(tile) != null;
                base = scenerySupport(BoardGroundCut.digs(feature.asset()) ? ground.undug() : ground,
                      onDeck ? ground.deck() : null, supports,
                      BoardGeometry.centerX(tile.coords()), BoardGeometry.centerY(tile.coords()),
                      feature.kind() != BoardScene.FeatureKind.TREE && onDeck ? BoardBridge.deckZ(tile) : Float.NEGATIVE_INFINITY);
                placeAuthored(instance, px, py, base, BoardGroundCut.fitted(scene, tile, feature));
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
                float horizontalScale = feature.kind() == BoardScene.FeatureKind.TREE
                      ? BoardTreeCoverage.scale(feature, sourceHeight) * BoardGeometry.hexScale() : fungus || coral
                      || feature.asset().startsWith("vegetation/foliage-") ? verticalScale
                      : feature.modelScale() * BoardGeometry.hexScale();
                instance.transform.setToTranslation(px, py,
                      base + feature.elevation() * BoardGeometry.level()
                            - (metal ? bounds.min.z * verticalScale + .02f * BoardGeometry.level() : 0))
                      .rotate(Vector3.Z, feature.rotation())
                      .scale(horizontalScale * (float) feature.stretch().x(), horizontalScale * (float) feature.stretch().y(),
                            verticalScale * (float) feature.stretch().z());
            }
            bounds.mul(instance.transform);
            if (BoardFlag.ASSET.equals(feature.asset())) { bounds.set(GpuFlag.bounds(instance.transform)); }
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
            var prop = new Prop(tile.coords(), instance, levels, bounds, standTree ? feature.asset() : null,
                  feature.kind() == BoardScene.FeatureKind.LIMB || metal, building,
                  object != null && !foliageModel(model) || feature.kind() == BoardScene.FeatureKind.BUILDING
                        || feature.asset().startsWith("buildings/"),
                  false, feature.kind() == BoardScene.FeatureKind.INDUSTRIAL);
            prop.editorBounds = editorBounds;
            prop.parts = feature.parts();
            BoardGroundCut.showParts(instance, prop.parts);
            if (standTree) { prop.plantFeature = feature; }
            prop.infrastructure = routeFeature(tile, feature);
            if (feature.kind() == BoardScene.FeatureKind.BUILDING || authored != null) {
                prop.buildingColours = feature.colours();
            }
            prop.scatter = feature.kind() == BoardScene.FeatureKind.SCATTER;
            // Placed objects, from either file format, under a quarter hex: zoomed-out views skip them (drawn).
            prop.small = authored == null && object != null && !BoardRail.route(feature)
                  && !standTree && !foliageModel(model) && geyser == null
                  && bounds.getDimensions(new Vector3()).len() < TreeLod.SMALL_OBJECT * BoardGeometry.width();
            prop.decorationId = editable ? object.id() : null;
            if (authored != null) {
                prop.decorationOwner = authoredLayout.anchor(); prop.supportTriangles = authored.roof();
                prop.editorBounds = new BoundingBox(authoredPrepared.localBounds());
                prop.nightLights = GpuAuthoredBuilding.nightLights(authoredLayout);
                prop.updateLights(scene.height());
            }
            if (BoardBillboard.ASSET.equals(feature.asset())) {
                prop.nightLights = BoardBillboardMesh.lights(object.billboard());
                prop.updateLights(scene.height());
            }
            if (placedObject || authored != null) { prop.decorationFeature = feature; }
            prop.decorationAnchorZ = instance.transform.val[Matrix4.M23] - feature.elevation() * BoardGeometry.level();
            // A click on a maglev's pier selects its route (batch-10 answer 8): the beam's triangles and its piers'.
            prop.receiver = authored != null && feature.kind() == BoardScene.FeatureKind.BUILDING ? "building" : BoardFeatures.receiver(feature);
            chunk.props.add(prop);
            Model glass = multiHexLayout == null ? null : assets.multiHexGlass(multiHexLayout);
            if (glass != null) {
                var pane = new ModelInstance(glass);
                pane.transform.set(instance.transform);
                chunk.glass.add(pane);
                chunk.bounds.ext(pane.calculateBoundingBox(new BoundingBox()).mul(pane.transform));
            }
            if ((feature.decoration() == null || authored != null && feature.kind() == BoardScene.FeatureKind.BUILDING) && (feature.kind() == BoardScene.FeatureKind.BUILDING
                  || feature.kind() == BoardScene.FeatureKind.INDUSTRIAL || feature.kind() == BoardScene.FeatureKind.FUEL_TANK
                  || feature.kind() == BoardScene.FeatureKind.PROP)) {
                supports.add(prop);
            }
            if (feature.kind() == BoardScene.FeatureKind.BUILDING && !wallBuilding && tile.cover().interiors() != BuildingInteriors.NO
                  && (authored == null || authored.interior() != null)) {
                boolean open = building != null && ConnectedKit.open(scene, tile, Math.round(feature.height()));
                // A closed kit building's interior is built when a unit first stands in its hex (animate): until then
                // nothing shows it, and a city's would be hundreds of megabytes of floors.
                if (building != null && !open) {
                    prop.interiorPending = true;
                } else {
                    int floors = tile.cover().interiors() == BuildingInteriors.NO_FLOORS ? 1 : Math.round(feature.height());
                    interior(prop, authored != null ? authored.interior() : building == null ? assets.interior(model, floors)
                          : building.interior(), open, chunk.props, chunk.struts);
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
            if (!Float.isFinite(height) && receiver.equals("bridge") && BoardBridge.standing(owner) != null) {
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
                if (hitSupport(support, ray, point)) { height = Math.max(height, point.z); }
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
            if (hitSupport(prop, ray, point)) { height = Math.max(height, point.z); }
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

    /** The actual root outline, not its enclosing circle: narrow crystal fans may stand near a hex or cliff edge. */
    private static List<Vector3> crystalRoots(Model model, float rootTop) {
        var result = new ArrayList<Vector3>();
        for (Mesh mesh : model.meshes) {
            int stride = mesh.getVertexSize() / Float.BYTES;
            int position = mesh.getVertexAttribute(VertexAttributes.Usage.Position).offset / Float.BYTES;
            float[] vertices = new float[mesh.getNumVertices() * stride];
            mesh.getVertices(vertices);
            for (int i = position; i < vertices.length; i += stride) {
                if (vertices[i + 2] > rootTop) { continue; }
                var root = new Vector3(vertices[i], vertices[i + 1], 0);
                if (result.stream().noneMatch(p -> p.epsilonEquals(root, .001f))) { result.add(root); }
            }
        }
        return List.copyOf(result);
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
        final Map<Model, List<Vector3>> crystalRoots = new IdentityHashMap<>();
        final Map<Coords, BoardSurface> plantSupports;
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
            plantSupports = new HashMap<>(prepared.plantSupports());
            this.progress = progress;
            width = Math.min(CHUNK_SIZE, scene.width() - x);
            height = Math.min(CHUNK_SIZE, scene.height() - y);
            chunk.lod = lod;
            chunk.columns = columns;
            chunk.tessellated = tessellated;
            chunk.charts = new GpuMaterialCache.Charts(scene, x, y, floor);
            chunk.charts.keep(source == null ? null : source.charts, prepared.reused());
            chunk.paintOnlyUpdate = prepared.paintOnly();
            if (chunk.paintOnlyUpdate) { progress.begin("paint", width * height); return; }
            GpuTextures<BoardScene.Pixels> packed = prepared.roadMasks();
            Map<BoardScene.Pixels, BoardScene.Pixels> masks = packed != null ? packed.prepacked()
                  : Prepared.roadMasks(prepared.roads());
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
                // A rebuild that keeps its roads (a structure coming down beside them) keeps their atlas: packing and
                // uploading a city chunk's masks again takes tens of milliseconds of the frame it starts in.
                if (source != null && source.roadMasks != null && source.roadMasks.holds(masks.keySet())) {
                    chunk.roadMasks = source.roadMasks.share();
                    if (packed != null) { packed.dispose(); }
                } else {
                    // Packed ahead (Prepared#packed): this only uploads its pages.
                    if (packed != null) { chunk.roadMasks = packed; }
                    chunk.roadMasks.update(masks);
                }
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
                        Prop copy = new Prop(coords, copy(prop.instance), prop.levels, prop.bounds,
                              prop.treeAsset, prop.hardSurface, prop.building, prop.structure, prop.floors, prop.industrial);
                        copy.scatter = prop.scatter;
                        copy.infrastructure = prop.infrastructure;
                        copy.openShell = prop.openShell;
                        copy.interiorPending = prop.interiorPending;
                        copy.small = prop.small;
                        copy.decorationId = prop.decorationId;
                        copy.decorationOwner = prop.decorationOwner; copy.supportTriangles = prop.supportTriangles;
                        copy.editorBounds = prop.editorBounds;
                        copy.decorationFeature = prop.decorationFeature;
                        copy.plantFeature = prop.plantFeature;
                        copy.decorationAnchorZ = prop.decorationAnchorZ;
                        copy.receiver = prop.receiver;
                        // Its paint, which every level it draws is tinted with (level), and its lights at night.
                        copy.buildingColours = prop.buildingColours;
                        copy.nightLights = prop.nightLights;
                        copy.drawnLights = prop.drawnLights;
                        // The copy draws the level the original drew, and still picks the near level.
                        copy.pickingTriangles = prop.pickingTriangles;
                        copy.lod = prop.lod;
                        copy.parts = prop.parts;
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

        /** Classic's cheap columns, a biodome's garden and a root past what the workers prepared are built on first use. */
        BoardSurface plantSupport(BoardScene.Tile tile) {
            return plantSupports.computeIfAbsent(tile.coords(), at -> chunk.columns
                  ? BoardSurface.columns(scene, tile, TerrainLod.FULL) : new BoardSurface(scene, tile));
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
                    // Level tops never curve; where their ground does, it is their material's ordinary copy.
                    Material copy = GpuTessellation.curvedCopy(GpuFamilyPrograms.unflat(part.material));
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
                // A kit's interiors that are built draw as shared instances (cacheProps); none is built here.
                if (prop.building != null && prop.building.builtInterior() != null) { chunk.sharedInteriors.add(prop.building.builtInterior()); }
                if (prop.tree()) {
                    chunk.treeDiameter = Math.max(chunk.treeDiameter, prop.treeDiameter);
                    // Plain trees fill the stand below (restand); scatter keeps its own stand.
                    if (prop.scatter) {
                        chunk.scatterDiameter = Math.max(chunk.scatterDiameter, prop.treeDiameter);
                        chunk.scatterStand.add(prop.treeAsset, prop.instance().transform, prop.treeDiameter);
                    }
                    var glow = GpuFungus.light(scene.height(), prop.coords(), prop.treeAsset, prop.bounds());
                    if (glow != null) { chunk.fungalLights.add(glow); }
                    chunk.spores.addAll(GpuFungus.emitters(prop.treeAsset, prop.instance().transform));
                    if (BoardFungus.COVER.contains(prop.treeAsset)) {
                        fungalHexes.computeIfAbsent(prop.coords(), at -> new BoundingBox().inf()).ext(prop.bounds());
                    }
                }
                if (!prop.tree() && !prop.translucent) {
                    chunk.cutaways.add(prop);
                }
            }
            fungalHexes.forEach((at, colony) -> chunk.spores.add(GpuFungus.cloud(at, colony)));
            chunk.restand(destruction::felled);
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
            // Its new props take the view's levels before their caches pack them: chosen afterwards (updateDetail), a
            // level packs the chunk's props a second time in the frame that installs it.
            if (!Float.isNaN(detailPixelsPerUnit)) {
                for (Prop prop : chunk.props) { prop.detail(detailPixelsPerUnit); }
            }
            refall(chunk);
            // Building shells' programs and those programs' twins link behind the loading stage (Prepass.OBJECTS).
            for (Renderable part : chunk.shadowPropRenderables) {
                if (prepass(part.material) == Prepass.OBJECTS) {
                    prepareTerrainMaterial(part.material, part.meshPart.mesh.getVertexAttributes());
                }
            }
            // Cover that stands blended (a crystal) falls solid in the debris batches (breakGrove, fallMaterial): that
            // program links behind the loading stage too.
            for (Prop prop : chunk.props) {
                var feature = prop.plantFeature != null ? prop.plantFeature : prop.decorationFeature;
                if (!prop.translucent || prop.standing() || feature == null || !BoardFeatures.cover(feature)) { continue; }
                for (Renderable part : GpuTerrainDepth.snapshot(List.of(prop.instance()))) {
                    if (blended(part.material) || GpuCrystalTransmission.ruby(part.material)) {
                        prepareTerrainMaterial(GpuTreeInstances.instancedMaterial(fallMaterial(part.material)),
                              part.meshPart.mesh.getVertexAttributes());
                    }
                }
            }
            // A structure that comes down leaves rubble (Hex#destroyStructure): scenery the board may not have drawn yet,
            // which draws loose (cacheDecorations). Its program links behind the loading stage too; every pile is built
            // of the same materials, so one stands for all.
            if (!rubblePrepared && !editable && chunk.tileMeshes.keySet().stream().map(scene::tile)
                  .anyMatch(tile -> tile != null && BoardScene.TileTransition.structure(tile))) {
                rubblePrepared = true;
                ModelInstance pile = new ModelInstance(assets.model(RUBBLE_PILE));
                for (Material material : pile.materials) { groundScenery(material); }
                for (Renderable part : GpuTerrainDepth.snapshot(List.of(pile))) {
                    opaque(part.material);
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
                }
                List<Array<Renderable>> layers = new ArrayList<>(List.of(chunk.shadowPropRenderables, chunk.routeRenderables));
                for (List<ModelInstance> layer : List.of(chunk.overlays, chunk.surfaceDecals, chunk.tilesetDecals, chunk.authoredPaint)) {
                    layers.add(GpuTerrainDepth.snapshot(layer));
                }
                // A section (a theme's card, the side view) draws each object's near level as it is (renderEditorSection).
                for (Prop prop : chunk.props) {
                    if (!prop.floors) { layers.add(GpuTerrainDepth.snapshot(List.of(prop.near()))); }
                }
                for (Array<Renderable> layer : layers) {
                    for (Renderable part : layer) { prepareTerrainMaterial(part.material, part.meshPart.mesh.getVertexAttributes()); }
                }
                for (Renderable part : chunk.sharedProps) {
                    prepareTerrainMaterial(GpuTreeInstances.instancedMaterial(part.material),
                          part.meshPart.mesh.getVertexAttributes());
                }
                prepareDecorations(chunk);
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
            var parts = GpuTerrainDepth.snapshot(List.of(prop.instance()));
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
    private boolean sculptedTriangles(GpuTerrainLayer solid, Material material, boolean split,
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
            sculptedGroups(solid, material, true, surface, tops, shore, scene, surfaces);
            if (!walls.isEmpty()) {
                solid.addTriangles(GpuMaterialCache.wall(material),
                      mesh -> chartedFaces(mesh, surface, walls, places, shore));
                sloped = true;
            }
        }
        sculptedGroups(solid, material, false, surface, rest, shore, scene, surfaces);
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
        // Cut sides can meet at the same position with distinct face normals or fixed texture frames.
        record Corner(Vector3 point, BoardRelief.Shade shade, float x, float y) { }
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
                var shade = surface.relief.shade(p);
                corners[k] = indices.computeIfAbsent(new Corner(p, shade, x, y),
                      key -> chartedVertex(mesh, sculptVertex(p, shade, shore, surface), x, y));
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
            boolean top = tops && GpuMaterialCache.cacheable(material);
            boolean divides = dividesTops(material);
            List<GpuSurfaceBlend.Triangle> flat = new ArrayList<>(), other = new ArrayList<>();
            for (var triangle : plain) {
                boolean level = divides && flatVertex(triangle.a().vertex()) && flatVertex(triangle.b().vertex())
                      && flatVertex(triangle.c().vertex());
                (level ? flat : other).add(triangle);
            }
            blendedTriangles(solid, top ? GpuMaterialCache.top(material) : material, palette, boundary, other, null, null);
            Material level = GpuFamilyPrograms.flat(material);
            blendedTriangles(solid, top ? GpuMaterialCache.top(level) : level, palette, boundary, flat, null, null);
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
                  preparedPatch.source().coords(), preparedPatch.flat(), vertexCoat, coverage, curves, curves ? supports : null));
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
        for (Attribute attribute : volcanic) { material.set(attribute.copy()); }
        material.set(new Sculpt(family), new Ground(-1));
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
            for (Attribute attribute : volcanic) { material.set(attribute.copy()); }
        }
        return material;
    }

    /** Faces in {@code material} ({@code top}: its cache tops' copy), the level tops among them in a copy of their own. */
    private void sculptedGroups(GpuTerrainLayer solid, Material material, boolean top, BoardSurface surface,
          List<BoardSurface.Face> faces, float shore, BoardScene scene, Map<Coords, BoardSurface> surfaces) {
        boolean divides = dividesTops(material);
        List<BoardSurface.Face> flat = new ArrayList<>(), other = new ArrayList<>();
        for (var face : faces) {
            boolean level = divides && flatShade(surface.relief.shade(face.a())) && flatShade(surface.relief.shade(face.b()))
                  && flatShade(surface.relief.shade(face.c()));
            (level ? flat : other).add(face);
        }
        if (!other.isEmpty()) {
            solid.addTriangles(top ? GpuMaterialCache.top(material) : material,
                  mesh -> sculptedFaces(mesh, surface, other, shore, scene, surfaces));
        }
        if (!flat.isEmpty()) {
            Material level = GpuFamilyPrograms.flat(material);
            solid.addTriangles(top ? GpuMaterialCache.top(level) : level,
                  mesh -> sculptedFaces(mesh, surface, flat, shore, scene, surfaces));
        }
    }

    private boolean dividesTops(Material material) {
        return familyPrograms.enabled() && GpuFamilyPrograms.divides(material, Sculpt.TYPE);
    }

    /** A vertex a level top may have, as sculptVertex writes it: ground kind, on a gentle slope. */
    private static boolean flatShade(BoardRelief.Shade shade) {
        return shade != null && (shade.kind() == BoardRelief.Kind.GROUND || shade.kind() == BoardRelief.Kind.SUBMERGED_CLIFF)
              && shade.normal().z >= GpuFamilyPrograms.FLAT_Z;
    }

    private static boolean flatVertex(MeshPartBuilder.VertexInfo vertex) {
        return vertex.color.b < .125f && vertex.normal.z >= GpuFamilyPrograms.FLAT_Z;
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
        if (liquid.molten()) { return GpuMagmaShader.material(assets, falling ? GpuMagmaShader.FALL : GpuMagmaShader.LAVA, field); }
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
        // Lake slabs never read the base surface's diffuse (terrain-ice.glsl).
        Material ice = GpuIceShader.lake(material(rainNoise, true));
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
        if (flagRevisions.containsKey(unit)) { return GpuFlag.bounds(unit.transform); }
        return frameBounds == null ? UnitBounds.world(unit) : frameBounds.get(unit);
    }

    /** Unit instances already contain the shared animation's current position; hidden units never enter this list. */
    void animate(float delta, List<ModelInstance> units) {
        animate(delta, units, DEFAULT_BUILDING_OPACITY);
    }

    /** Cloth out of the last drawn view holds its pose, as does cloth waiting for its turn; first it takes one at all. */
    private void animateFlag(Prop prop, List<ModelInstance> units, boolean turn) {
        GpuFlag flag = assets.flagCloth(prop.decorationFeature.decoration());
        if (flag == null) { return; }
        ModelInstance cloth = prop.instance();
        BoundingBox reach = GpuFlag.bounds(cloth.transform);
        if (flag.revision == 0 || turn && (flagView == null || flagView.frustum.boundsInFrustum(reach))) {
            var obstacles = new ArrayList<BoundingBox>();
            for (Chunk nearby : chunks) {
                if (!nearby.bounds.intersects(reach)) { continue; }
                for (Prop solid : nearby.props) {
                    if (!isFlag(solid) && solid.bounds().intersects(reach)) { obstacles.add(solid.bounds()); }
                }
            }
            for (ModelInstance unit : units) {
                BoundingBox bounds = unitBounds(unit);
                if (bounds.intersects(reach)) { obstacles.add(bounds); }
            }
            flag.animate(cloth.transform, vegetationPhase, wind, vacuum, obstacles,
                  flag.ground(cloth.transform, flagScene, this::flagGroundHeight));
        }
        flagRevisions.put(cloth, flag.revision);
    }

    /** Whether the waves advance this frame ({@link BoardQuality#halfRateWaves}); they follow the clock. */
    private boolean wavesDue() {
        if (!BoardQuality.current.halfRateWaves || ocean.texture() == null) { return true; }
        waveFrame = !waveFrame;
        if (waveFrame) { return false; }
        if (flagView == null) { return true; }
        for (Chunk chunk : chunks) {
            if (chunk.waterField != null && flagView.frustum.boundsInFrustum(chunk.bounds)) { return true; }
        }
        return false;
    }

    void animate(float delta, List<ModelInstance> units, float buildingAlpha) {
        // A level's fragments install between falls, one a frame, after the frame's strike: no fall's frame pays for them.
        if (fragments && destruction.isEmpty()) { assets.installFragments(); }
        float nextBuilding = MathUtils.clamp(buildingAlpha, 0, 1);
        boolean changedOpacity = nextBuilding != buildingOpacity;
        // A building faded away fully casts no shadow (renderDepth).
        if (changedOpacity && (nextBuilding == 0 || buildingOpacity == 0)) { shadowMap.invalidate(); }
        buildingOpacity = nextBuilding;
        clock += delta;
        magmaClock += delta * (float) Math.sqrt(gravity / BoardAtmosphere.STANDARD_GRAVITY);
        // A shared, continuous gust clock: stronger wind moves faster without rephasing when the slider changes.
        vegetationPhase = (vegetationPhase + delta * (.65f + 3.35f * wind.z)) % MathUtils.PI2;
        // The wave simulations run only for fields to show: none on Classic chunks, none in the Tactical View.
        boolean lava = chunks.stream().anyMatch(chunk -> chunk.lavaField != null);
        if (lava) { lavaOcean.update(clock, wind, gravity); }
        else if (!lava && lavaOcean.texture() != null) { lavaOcean.reset(); }
        boolean water = proceduralWater && chunks.stream().anyMatch(chunk -> chunk.waterField != null);
        if (water && waterEffects) {
            if (wavesDue()) { ocean.update(clock, wind, gravity); }
            waders.update(coverScene, units, units.stream().map(this::unitBounds).toList(), delta);
        } else if (!water && ocean.texture() != null) { ocean.reset(); }
        List<BoundingBox> occupied = hasCutaways && buildingOpacity < 1
              ? units.stream().map(this::unitBounds).toList() : List.of();
        flagRevisions.clear();
        // At a steady frame rate each flag moves on every other frame, half of them each frame.
        boolean everyFrame = delta > 1 / 40f;
        flagFrame++;
        int flagIndex = 0;
        for (Chunk chunk : chunks) {
            chunk.liquidMaterials.forEach(this::animateLiquid);
            for (Prop prop : chunk.flags()) { animateFlag(prop, units, everyFrame || (flagFrame + flagIndex++ & 1) == 0); }
            if (chunk.cutaways.isEmpty()) { continue; }
            boolean unitsHere = false;
            for (BoundingBox unit : occupied) { unitsHere |= chunk.bounds.intersects(unit); }
            // Without units or faded props, nothing in this chunk fades or returns.
            if (!unitsHere && !changedOpacity && chunk.faded.isEmpty()) { continue; }
            Set<Coords> occupiedHexes = new HashSet<>();
            for (BoundingBox unit : occupied) {
                if (!chunk.bounds.intersects(unit)) { continue; }
                for (Prop prop : chunk.cutaways) {
                    BoardScene.Tile tile = coverScene.tile(prop.coords);
                    // A unit on the roof is not inside, whatever of the roof (parapets, fittings, a vault) reaches its
                    // bounds. The Tactical View still takes the building away, so that none of it covers the token.
                    boolean onRoof = buildingOpacity > 0 && tile.cover().height() > 0 && unit.min.z
                          > (tile.elevation() + tile.cover().height() - ROOF_REACH) * BoardGeometry.level();
                    // The shell tells whether its hex is occupied: an interior is its shell's.
                    if (!prop.industrial && tile.cover().interiors() != BuildingInteriors.NO
                          && !prop.floors && !onRoof && prop.bounds().intersects(unit)) {
                        occupiedHexes.add(prop.coords());
                    }
                }
            }
            // By index: an interior built here joins the list right after its shell.
            for (int index = 0; index < chunk.cutaways.size() && !occupiedHexes.isEmpty(); index++) {
                Prop prop = chunk.cutaways.get(index);
                if (prop.interiorPending && occupiedHexes.contains(prop.coords())) { showInterior(chunk, prop); }
            }
            // One fade rule for every prop: the whole hex turns see-through around its unit, the shell and the interior
            // with all its floors, and a placed object with them like a packed prop; only route markers never fade.
            // Neither Chunk#cacheProps(omitFaded) nor #addDecorations draws a faded prop opaque: renderTransparent
            // draws it.
            Set<Prop> faded = new HashSet<>();
            for (Prop prop : occupiedHexes.isEmpty() ? List.<Prop>of() : chunk.cutaways) {
                if (!prop.route() && !prop.industrial && occupiedHexes.contains(prop.coords())) { faded.add(prop); }
            }
            boolean changedOccupancy = !faded.equals(chunk.faded);
            if (changedOccupancy || changedOpacity) {
                scarSurfaceRevision++;
                Set<Prop> ordered = new java.util.LinkedHashSet<>();
                for (Prop prop : chunk.cutaways) { if (faded.contains(prop)) { ordered.add(prop); } }
                chunk.faded = Collections.unmodifiableSet(ordered);
                for (Prop prop : chunk.cutaways) {
                    if (prop.route()) { continue; }
                    // The floors stay more visible than the walls around them.
                    float opacity = !faded.contains(prop) ? Float.NaN
                          : prop.floors ? MathUtils.lerp(buildingOpacity, 1, .25f) : buildingOpacity;
                    for (Material material : prop.instance().materials) { fade(prop.instance(), material, opacity); }
                }
                if (changedOccupancy) {
                    refall(chunk);
                    // A building faded away fully casts no shadow (renderDepth).
                    if (buildingOpacity == 0) { shadowMap.invalidate(); }
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
        // The hex labels ask for every labelled roof of the board at each change: a walk of the chunk's every prop for
        // each took up to 18 ms on a city.
        for (Prop prop : editable ? chunks.get(index).props : chunks.get(index).propsAt(coords)) {
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
     * A drawn object: its broad-phase world bounds and oriented outline, its anchor's level and world point, its placement (drags included),
     * whether this zoom shows it ({@link #drawn}), and whether it is a decal's paint.
     */
    record EditorObject(String id, BoundingBox bounds, OrientedBoundingBox outline, float anchorLevel, Vector3 anchor,
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
                    if (GpuAuthoredBuilding.supports(prop.decorationFeature)) {
                        changed |= previewAuthored(chunk, prop, placement);
                        continue;
                    }
                    BoardScene.Feature next = placement == null ? prop.decorationFeature : placement.feature();
                    Coords coords = placement == null ? prop.coords : placement.coords();
                    // Another model, other colours or another billboard need the chunk's rebuild, which installs that model.
                    if (!prop.decorationFeature.asset().equals(next.asset())
                          || !prop.decorationFeature.colours().equals(next.colours())
                          || !prop.decorationFeature.decoration().billboard().equals(next.decoration().billboard())) { continue; }
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
                    if (preview != null && isFlag(prop)) { prop.previewBounds.set(GpuFlag.bounds(preview.transform)); }
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
                    if (trees) { chunk.restand(destruction::felled); }
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
                updateBuildingLights();
            }
        });
    }

    /** Borrow the shell immediately; hide stale interiors until the worker installs the final cuts after release. */
    private boolean previewAuthored(Chunk chunk, Prop prop, EditorPlacement placement) {
        if (placement == null) { return false; }
        var next = placement.feature();
        var original = prop.decorationFeature;
        if (!original.asset().equals(next.asset()) || original.decoration().levels() != next.decoration().levels()) { return false; }
        if (next.equals(prop.previewFeature == null ? original : prop.previewFeature) && placement.coords().equals(prop.owner())) { return false; }
        float unit = BoardGeometry.hexScale();
        Matrix4 before = new Matrix4().scale(unit, unit, unit).mul(GpuAuthoredBuilding.frame(prop.decorationOwner, original.decoration(), original.elevation()));
        Matrix4 after = new Matrix4().scale(unit, unit, unit).mul(GpuAuthoredBuilding.frame(placement.coords(), next.decoration(), next.elevation()));
        boolean originalPose = java.util.Arrays.equals(before.val, after.val);
        if (originalPose) { chunk.hiddenInteriors.remove(prop.coords); }
        else { chunk.hiddenInteriors.add(prop.coords); }
        Matrix4 delta = after.mul(before.inv());
        prop.previewFeature = next;
        prop.previewOwner = placement.coords();
        prop.previewAnchorZ = next.elevation() * BoardGeometry.level();
        prop.previewInstance = copy(prop.instance);
        GpuBuilding.tint(prop.previewInstance, next.colours());
        prop.previewInstance.transform.set(delta).mul(prop.instance.transform);
        // Like stale interior cuts, lighting waits for the final installed placement after a drag.
        if (originalPose) { prop.updateLights(coverScene.height()); }
        else { prop.drawnLights = List.of(); }
        prop.previewBounds = new BoundingBox(prop.bounds).mul(delta);
        chunk.bounds.ext(prop.bounds()); shadowBounds.ext(prop.bounds());
        return true;
    }

    /** Installed terrain only: collision follows the visible chunk through edits and detail changes. No off-board floor. */
    float flagGroundHeight(Vector3 point) {
        var tile = coverScene == null ? null : BoardGeometry.tile(coverScene, point.x, point.y);
        TileMesh mesh = tile == null ? null : editorTile(tile.coords());
        if (mesh == null) { return Float.NEGATIVE_INFINITY; }
        Chunk receiving = chunks.get(tile.coords().getX() / CHUNK_SIZE * chunkRows + tile.coords().getY() / CHUNK_SIZE);
        var support = tacticalSurface(coverScene, receiving, tile.coords(), floor);
        return BoardSurface.sampleHeight(support.faces(), point.x, point.y, BoardGeometry.groundZ(tile), true);
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
            ModelInstance ghost = new ModelInstance(BoardBillboard.ASSET.equals(feature.asset())
                  ? assets.billboardGhost(feature.decoration().billboard(),
                        BoardBillboardMesh.legs(feature.decoration(), coverScene.tile(coords).elevation()))
                  : assets.decoration(feature.asset(), feature.colours()));
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
        return feature.asset().equals("bridge") || feature.kind() == BoardScene.FeatureKind.BUILDING || BoardRail.route(feature)
              || GpuAuthoredBuilding.supports(feature)
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
        var hidden = lifts != null ? lifts.hidden(chunk) : editorRoutes == null ? Hidden.NONE : editorRoutes.hidden(chunk);
        // Bypass the packed interior meshes immediately, without repacking them on every pointer event.
        return chunk.hiddenInteriors.isEmpty() || hidden.props() ? hidden
              : new Hidden(hidden.ground(), hidden.overlays(), hidden.paint(), true);
    }

    /**
     * Presents the scene change the playback plays ({@link UnitPlayback#transition}) at {@code progress}, each frame
     * before the terrain updates; null while none plays. While it plays, the terrain builds each hex it moves at the
     * lower of its two heights, so that the ground beside it keeps its walls down to there; once it ends, its platforms
     * stay at their end until the terrain has installed its after tiles. Classic columns snap.
     */
    void present(BoardScene.TileTransition transition, float progress) {
        presentCollapses(transition, progress);
        boolean shown = !installedColumns && coverScene != null;
        if (lifts != null && (!shown || (transition != null ? lifts.transition != transition
              : heights(lifts.transition, BoardScene.TileTransition.Change::after)))) {
            lifts.dispose();
            lifts = null;
            refreshShadows();
        }
        if (!shown || (transition == null || transition.changes().isEmpty()) && lifts == null) { return; }
        if (lifts == null) {
            lifts = new Lifts(transition);
            refreshShadows();
        }
        lifts.ending = transition == null;
        lifts.move(lifts.ending ? 1 : progress);
    }

    /**
     * Whether the terrain shows each hex {@code transition} moves at its lower height, from which its platform moves,
     * Classic columns needing nothing, and each structure it brings down has given way to its pieces or waited its
     * longest ({@link #COLLAPSE_WAIT_NANOS}).
     */
    boolean ready(BoardScene.TileTransition transition) {
        long now = System.nanoTime();
        return (installedColumns || heights(transition, Lifts::lower)) && collapsing.stream().noneMatch(fall ->
              fall.transition() == transition && !fall.collapse().started() && now - fall.since() < COLLAPSE_WAIT_NANOS);
    }

    /**
     * How long the playback waits for a collapse's pieces to take its structure's place: a rebuild slower than this
     * lets it go on, and the structure goes when the terrain installs the hex without it, as it did before pieces fell.
     */
    private static final long COLLAPSE_WAIT_NANOS = 5_000_000_000L;

    /**
     * A structure coming down ({@link BoardScene.TileTransition#collapses}) at {@code coords}: read from the terrain as
     * the transition began ({@code since}, in nanoseconds), its pieces take its place when the terrain installs the hex
     * without it.
     */
    private record Collapsing(BoardScene.TileTransition transition, Coords coords, GpuCollapse collapse, long since) { }

    /**
     * Reads the structures {@code transition} brings down from the installed terrain, which still draws them, as it
     * begins, and starts cutting them. Their pieces stay at rest until its clock runs, after the camera has framed them
     * ({@code progress} above 0), as their occupants' falls do; once it has ended they fall on. Pieces that never took
     * their structure's place by then (the transition was skipped) go.
     */
    private void presentCollapses(BoardScene.TileTransition transition, float progress) {
        presentFalls(transition, progress);
        collapsing.removeIf(fall -> {
            boolean dropped = fall.transition() != transition && !fall.collapse().started();
            if (dropped) {
                fall.collapse().dispose();
                destruction.landed(fall.coords());
            }
            return dropped;
        });
        for (Collapsing fall : collapsing) {
            if (fall.transition() != transition || progress > 0) { fall.collapse().release(); }
        }
        if (transition == null || transition.collapses().isEmpty() || coverScene == null
              || collapsing.stream().anyMatch(fall -> fall.transition() == transition)) { return; }
        // A transition that brings dozens down at once, as a nuclear blast does, breaks those nearest its middle, where the
        // camera frames it; the rest make way for what replaces them as the terrain installs it, as before pieces fell.
        var middle = GpuCollapse.middle(transition);
        var broken = transition.collapses().stream().filter(collapse -> !destruction.taken(collapse.coords()))
              .sorted(Comparator.comparingDouble(collapse ->
              BoardGeometry.center(collapse.coords(), collapse.before().elevation()).dst2(middle))).limit(MAX_COLLAPSES).toList();
        installedSettings.run(() -> {
            for (var collapse : broken) {
                var fall = collapse(collapse, broken.size());
                if (fall != null) { collapsing.add(new Collapsing(transition, collapse.coords(), fall, System.nanoTime())); }
            }
        });
    }

    /** At most this many hexes of one transition break into pieces. */
    private static final int MAX_COLLAPSES = 24;

    /** The transition whose kit structures were taken for their pieces, and whether they have begun to give way. */
    private BoardScene.TileTransition fallsFor;
    private boolean fallsStarted;
    /** A building's hexes give way one after another, this many playback seconds a hex from where it was hit. */
    private static final float FALL_CHAIN_SECONDS = .06f, FALL_CHAIN_LIMIT = 1.5f;

    /**
     * Takes the kit structures {@code transition} brings down from their chunks as it begins: their pieces stand where they
     * stood (GpuDestruction) and give way once its clock runs, after the camera has framed them ({@code progress} above
     * 0), hex after hex from the one hit. A transition that ends unframed lets them go at once. Classic columns snap.
     */
    private void presentFalls(BoardScene.TileTransition transition, float progress) {
        if (fallsFor != null && fallsFor != transition) {
            fallsFor.collapses().forEach(collapse -> destruction.start(collapse.coords(), 0));
            fallsFor = null;
        }
        if (transition == null || transition.collapses().isEmpty() || coverScene == null || installedColumns) { return; }
        if (fallsFor == null) {
            fallsFor = transition;
            fallsStarted = false;
            Set<Chunk> taken = new LinkedHashSet<>();
            installedSettings.run(() -> {
                for (var collapse : transition.collapses()) {
                    // A strike's front took it already: its pieces fall or lie there.
                    Chunk chunk = destruction.taken(collapse.coords()) ? null : takeFall(collapse, 0);
                    if (chunk != null) { taken.add(chunk); }
                }
            });
            if (!taken.isEmpty()) {
                taken.forEach(this::refall);
                // The structures leave the cached shadow too: their pieces cast over it.
                refreshShadows();
            }
            stampFalls();
        }
        if (progress > 0 && !fallsStarted) {
            fallsStarted = true;
            // From where a unit's weight brought it down, else from the hardest hit.
            var first = transition.collapses().stream().max(Comparator.comparing(
                  (BoardScene.TileTransition.Collapse collapse) -> !collapse.loads().isEmpty())
                  .thenComparingDouble(BoardScene.TileTransition.Collapse::strength)).orElseThrow().coords();
            for (var collapse : transition.collapses()) {
                destruction.start(collapse.coords(), Math.min(FALL_CHAIN_LIMIT, FALL_CHAIN_SECONDS * first.distance(collapse.coords())));
            }
        }
    }

    /**
     * Takes the kit structures standing at the collapse's hex for their pieces: every part of every node the terrain
     * draws there, but glass, which shatters to nothing; each in a solid copy of its material drawn from both sides (a
     * kit storey is open below), its mesh part's box its node's, about which the piece turns. The chunk that drew them,
     * or null where none stands.
     */
    private Chunk takeFall(BoardScene.TileTransition.Collapse change, float blast) {
        Coords at = change.coords();
        if (at.getX() < 0 || at.getY() < 0 || at.getX() / CHUNK_SIZE * chunkRows + at.getY() / CHUNK_SIZE >= chunks.size()) {
            return null;
        }
        Chunk chunk = chunks.get(at.getX() / CHUNK_SIZE * chunkRows + at.getY() / CHUNK_SIZE);
        TileMesh mesh = chunk.tileMeshes.get(at);
        if (mesh == null) { return null; }
        Array<Renderable> pieces = new Array<>();
        Set<GpuBuilding.Assembly> buildings = new HashSet<>();
        BoundingBox bounds = new BoundingBox().inf();
        Map<Material, Material> copies = new IdentityHashMap<>();
        for (Prop prop : mesh.props) {
            if (prop.building == null || isFlag(prop)) { continue; }
            ModelInstance drawn = prop.instance();
            // In fragments where its level's cut is in (KitFragments); else in whole modules, and the level is cut now.
            KitFragments broken = prop.building.kit().fragments(drawn.model);
            if (broken == null && fragments) { prop.building.kit().cutFragments(drawn.model); }
            for (Node node : drawn.nodes) { addPieces(drawn, node, copies, pieces, broken, false); }
            buildings.add(prop.building);
            bounds.ext(prop.bounds());
        }
        if (pieces.isEmpty()) { return null; }
        // What stands on the structure, not on the ground beside it, comes down with its roof. Its interior floors show
        // only in a cutaway, which a falling hex never draws (Chunk#cacheProps).
        for (Prop prop : mesh.props) {
            if (prop.building != null || prop.tree() || isFlag(prop) || prop.floors
                  || prop.bounds().min.z < bounds.min.z + BoardGeometry.level()) { continue; }
            ModelInstance drawn = prop.instance();
            for (Node node : drawn.nodes) { addPieces(drawn, node, copies, pieces, null, false); }
            carried.add(prop);
        }
        var middle = BoardGeometry.center(at, change.before().elevation());
        var push = new Vector2();
        int flags = change.loads().isEmpty() ? 0 : GpuDestruction.LOADED;
        if (change.from() != null && change.strength() > 0) {
            var from = BoardGeometry.center(change.from().coords(), change.from().elevation());
            // From above (a death from above) it drives the structure down; else it pushes it away, level.
            if (from.z > bounds.max.z) { flags |= GpuDestruction.LOADED; }
            else { push.set(middle.x - from.x, middle.y - from.y).nor().scl(blast > 0 ? blast : change.strength()); }
        }
        // Its pieces come down to the hex's ground: a hex inside a wide building holds its roof alone.
        bounds.min.z = Math.min(bounds.min.z, middle.z);
        destruction.take(at, pieces, buildings, bounds, middle.x, middle.y, push, flags);
        return chunk;
    }

    /**
     * Adds the node's parts and its children's as falling pieces, in {@code broken}'s fragments where it holds them.
     * {@code glass}: its blended parts too, solid.
     */
    private static void addPieces(ModelInstance instance, Node node, Map<Material, Material> copies,
          Array<Renderable> pieces, KitFragments broken, boolean glass) {
        // One box for the node's parts, in its mesh's frame: they turn about it as one piece.
        BoundingBox box = new BoundingBox().inf();
        var corner = new Vector3();
        for (NodePart part : node.parts) {
            if (!part.enabled || part.meshPart.size == 0) { continue; }
            box.ext(corner.set(part.meshPart.center).sub(part.meshPart.halfExtents));
            box.ext(corner.set(part.meshPart.center).add(part.meshPart.halfExtents));
        }
        for (NodePart part : node.parts) {
            // Glass is what blends in the model itself: a building faded around its occupants or the pointer still falls.
            Material own = instance.model.getMaterial(part.material.id);
            if (!part.enabled || part.meshPart.primitiveType != GL20.GL_TRIANGLES || part.meshPart.size == 0
                  || !glass && blended(own != null ? own : part.material)) { continue; }
            Renderable piece = instance.getRenderable(new Renderable(), node, part);
            if (!GpuTreeInstances.instanceable(piece.worldTransform)) { continue; }
            piece.material = copies.computeIfAbsent(part.material, GpuTerrain::fallMaterial);
            var fragments = broken == null ? null : broken.part(part.meshPart);
            if (fragments != null) {
                // Its fragments in its place: still one batch, each vertex moving with its fragment (tree-instances.glsl).
                piece.meshPart.set(fragments.range());
                piece.userData = fragments.fragments();
            }
            box.getCenter(piece.meshPart.center);
            box.getDimensions(piece.meshPart.halfExtents).scl(.5f);
            pieces.add(piece);
        }
        for (Node child : node.getChildren()) { addPieces(instance, child, copies, pieces, broken, glass); }
    }

    /** The kit levels the installed chunks draw break into fragments on their own thread, ahead of any fall (KitFragments). */
    private void cutFragments() {
        for (Chunk chunk : chunks) { cutFragments(chunk); }
    }

    private void cutFragments(Chunk chunk) {
        if (!fragments) { return; }
        for (Prop prop : chunk.props) {
            if (prop.building != null) { prop.building.kit().cutFragments(prop.instance().model); }
        }
    }

    /**
     * The rubble the rules leave where a structure fell, scenery the installed scene now draws on its hex, waits out of
     * sight under the falling pieces and rises once they are down (GpuDestruction#rubble): at the falls' hexes and at
     * those of the old path's collapses, whose pieces say when they land (#advanceCollapses). Its chunk draws it again
     * once its fall is over.
     */
    private void takeRubble() {
        Set<Coords> fallen = destruction.fallen();
        for (Collapsing fall : collapsing) { fallen.add(fall.coords()); }
        for (Coords at : fallen) {
            int index = at.getX() / CHUNK_SIZE * chunkRows + at.getY() / CHUNK_SIZE;
            if (at.getX() < 0 || at.getY() < 0 || index >= chunks.size()) { continue; }
            TileMesh mesh = chunks.get(index).tileMeshes.get(at);
            var tile = coverScene.tile(at);
            if (mesh == null || tile == null) { continue; }
            Array<Renderable> pieces = new Array<>();
            BoundingBox bounds = new BoundingBox().inf();
            Map<Material, Material> copies = new IdentityHashMap<>();
            for (Prop prop : mesh.props) {
                if (!rubble(prop) || rising.contains(prop)) { continue; }
                ModelInstance drawn = prop.instance();
                for (Node node : drawn.nodes) { addPieces(drawn, node, copies, pieces, null, false); }
                rising.add(prop);
                bounds.ext(prop.bounds());
            }
            if (pieces.isEmpty()) { continue; }
            var middle = BoardGeometry.center(at, tile.elevation());
            destruction.rubble(at, pieces, bounds, middle.x, middle.y);
        }
    }

    /** Terrain scenery's material (a geyser's, rubble's, a strongpoint's) shares the ground's lighting and weather response. */
    private void groundScenery(Material material) {
        if (!material.id.startsWith("geyser-") && !material.id.startsWith("rubble-")
              && !material.id.startsWith("fortified-")) { return; }
        material.set(new Ground(.15f));
        if (material.id.equals("geyser-lava")) {
            Material lava = GpuMagmaShader.material(assets, GpuMagmaShader.LAVA, null);
            for (Attribute attribute : lava) { material.set(attribute.copy()); }
        }
    }

    /** Rubble the rules leave where a structure fell: scenery its terrain owns (BoardFeatures#features), no object placed. */
    private static boolean rubble(Prop prop) {
        var feature = prop.decorationFeature;
        return feature != null && feature.kind() == BoardScene.FeatureKind.SCENERY && feature.decoration() == null;
    }

    /** A falling piece's copy of a drawn material: solid, whatever fade its building wore, from both sides. */
    private static Material fallMaterial(Material drawn) {
        var copy = new Material(drawn);
        opaque(copy);
        copy.set(IntAttribute.createCullFace(GL20.GL_NONE));
        return copy;
    }

    /** This frame's live shock fronts, which {@code source} writes (GpuArtilleryBlast#shocks) and counts. */
    void shocks(java.util.function.ToIntFunction<float[]> source) { shockCount = source.applyAsInt(shocks); }

    /** Runs the falls' clock on by {@code seconds} of playback under {@code gravity} standard gravities. */
    void advanceFalls(float seconds, float gravity) {
        int before = destruction.revision();
        destruction.advance(seconds, gravity, coverScene == null ? 0 : coverScene.width(),
              coverScene == null ? 0 : coverScene.height());
        // A pile that sank lay on a hex its chunk already shows without the structure.
        if (destruction.revision() != before) {
            stampFalls();
            carried.removeIf(prop -> !destruction.taken(prop.coords));
            rising.removeIf(prop -> !destruction.taken(prop.coords));
            broken.removeIf(prop -> !destruction.felled(prop.instance().transform));
        }
    }

    /**
     * The installed chunks agree with the falls as they now are: called once the chunks holding the hexes that changed
     * have snapshotted again (refall). Chunks kept at another detail level snapshot again when they come back.
     */
    private void stampFalls() {
        for (Chunk chunk : chunks) { chunk.fallsRevision = destruction.revision(); }
    }

    /**
     * A strike going off now: the kit structures it brings down leave their chunks for their pieces, and each piece gives
     * way as its front reaches it (GpuArtilleryBlast#shockArrival, on the blast's clock, twice the playback's). Classic
     * columns snap.
     */
    void strike(BoardScene.Detonation detonation) {
        if (coverScene == null || installedColumns || detonation.collapses().isEmpty() && detonation.felled().isEmpty()) {
            return;
        }
        var result = detonation.result();
        float radius = GpuArtilleryBlast.nuclearRadius(result.stats());
        var groundZero = BoardGeometry.center(result.coords(), result.elevation());
        Set<Chunk> taken = new LinkedHashSet<>();
        installedSettings.run(() -> {
            for (var collapse : detonation.collapses()) {
                if (destruction.taken(collapse.coords())) { continue; }
                var middle = BoardGeometry.center(collapse.coords(), collapse.before().elevation());
                float distance = Vector2.dst(middle.x, middle.y, groundZero.x, groundZero.y);
                // Stronger toward the epicentre: up to four heavy hits' push beside it, one at the blast's edge. A
                // conventional explosion pushes as its hit does (TileTransition#blast), never more than a heavy hit.
                float near = 1 - MathUtils.clamp(distance / radius, 0, 1);
                Chunk chunk = takeFall(collapse, result.kind() == megamek.common.NuclearDetonation.Kind.EXPLOSION ? 0
                      : 1 + 3 * near * near);
                if (chunk == null) { continue; }
                taken.add(chunk);
                destruction.blastFrom(collapse.coords(), groundZero.x, groundZero.y,
                      GpuArtilleryBlast.shockSlowness(distance, radius) / GpuArtilleryBlast.SECONDS_PER_PLAYBACK);
                // A fuel tank an explosion names is the one that went off in it (AttackAnimationCapture): it bursts.
                if (result.kind() == megamek.common.NuclearDetonation.Kind.EXPLOSION
                      && collapse.before().cover().structure() == BoardScene.Cover.FUEL_TANK) {
                    destruction.burst(collapse.coords());
                }
                destruction.start(collapse.coords(),
                      GpuArtilleryBlast.shockArrival(distance, radius) / GpuArtilleryBlast.SECONDS_PER_PLAYBACK);
            }
            for (var grove : detonation.felled()) {
                var middle = BoardGeometry.center(grove.coords(), 0);
                float distance = Vector2.dst(middle.x, middle.y, groundZero.x, groundZero.y);
                float arrival = GpuArtilleryBlast.shockArrival(distance, radius) / GpuArtilleryBlast.SECONDS_PER_PLAYBACK;
                // What stands in no stand (crystals, a skin's rows) bursts as the front reaches its hex.
                Chunk burst = breakGrove(grove, groundZero, arrival, false);
                if (burst != null) { taken.add(burst); }
                Chunk chunk = takeTrees(grove, groundZero);
                if (chunk == null) { continue; }
                taken.add(chunk);
                destruction.blastFrom(grove.coords(), groundZero.x, groundZero.y,
                      GpuArtilleryBlast.shockSlowness(distance, radius) / GpuArtilleryBlast.SECONDS_PER_PLAYBACK);
                destruction.startTrees(grove.coords(), arrival);
            }
        });
        if (!taken.isEmpty()) {
            taken.forEach(this::refall);
            refreshShadows();
        }
        stampFalls();
    }

    /**
     * A nuclear round's blast going off now (a Davy Crockett-M fired in combat): what its attack brought down falls with
     * its front from where it landed, as a scheduled strike's does, not at the phase's end.
     */
    void strike(megamek.common.ResolvedAttack result, Coords landing) {
        var stats = GpuArtilleryBlast.nuclearStats(result.shot());
        var ground = coverScene == null ? null : coverScene.tile(landing);
        if (stats == null || ground == null) { return; }
        // At the hex's level: the round's impact height adds its target's, which would drive the structures down.
        var record = new megamek.common.NuclearDetonation(result.id(), coverScene.boardId(), landing, ground.elevation(),
              stats.baseDamage, stats.degradation, stats.secondaryRadius, stats.craterDepth);
        List<BoardScene.TileTransition.Collapse> falls = new ArrayList<>();
        for (var at : result.collapses()) {
            var tile = at.boardId() == coverScene.boardId() ? coverScene.tile(at.coords()) : null;
            if (tile != null && BoardScene.TileTransition.structure(tile)) {
                falls.add(BoardScene.TileTransition.collapse(coverScene, tile, BoardScene.TileTransition.blast(record, at.coords())));
            }
        }
        if (!falls.isEmpty()) { strike(new BoardScene.Detonation(record, falls)); }
    }

    /**
     * Fells the hex's stand trees the blast clears ({@code grove}, as its scene's features): they leave their chunk's
     * stand to fall away from ground zero (GpuDestruction). The chunk that drew them, or null where none stands.
     */
    private Chunk takeTrees(BoardScene.Detonation.Felled grove, Vector3 groundZero) {
        Coords at = grove.coords();
        Chunk chunk = chunkOf(at);
        if (chunk == null) { return null; }
        Set<BoardScene.Feature> features = Set.copyOf(grove.trees());
        List<GpuDestruction.Tree> trees = new ArrayList<>();
        BoundingBox bounds = new BoundingBox().inf();
        for (Prop prop : grovesTrees(chunk, grove)) {
            if (!prop.standing()) { continue; }
            trees.add(new GpuDestruction.Tree(prop.treeAsset, new Matrix4(prop.instance().transform), prop.treeDiameter));
            bounds.ext(prop.bounds());
        }
        if (trees.isEmpty()) { return null; }
        var middle = BoardGeometry.center(at, 0);
        var push = new Vector2(middle.x - groundZero.x, middle.y - groundZero.y);
        if (push.len2() < 1e-6f) { push.set(1, 0); }
        destruction.takeTrees(at, trees, features, bounds, middle.x, middle.y, push.nor());
        return chunk;
    }

    /** The chunk holding the hex, null off the board. */
    private Chunk chunkOf(Coords at) {
        int index = at.getX() / CHUNK_SIZE * chunkRows + at.getY() / CHUNK_SIZE;
        return at.getX() < 0 || at.getY() < 0 || index >= chunks.size() ? null : chunks.get(index);
    }

    /**
     * The chunk's cover of {@code grove}: its hex's props for its scene features, the trees of its stand
     * ({@link Prop#standing}) and what draws on its own (crystals, a skin's rows).
     */
    private static List<Prop> grovesTrees(Chunk chunk, BoardScene.Detonation.Felled grove) {
        Set<BoardScene.Feature> features = Set.copyOf(grove.trees());
        List<Prop> result = new ArrayList<>();
        for (Prop prop : chunk.props) {
            var feature = prop.plantFeature != null ? prop.plantFeature : prop.decorationFeature;
            if (prop.scatter || feature == null || !prop.coords().equals(grove.coords()) || !features.contains(feature)) {
                continue;
            }
            result.add(prop);
        }
        return result;
    }

    /** Splits the models of the cover an attack is about to break into their fragments, ahead of its hit (#breakTrees). */
    void prepareBreak(List<BoardScene.Detonation.Felled> broken) {
        if (!fragments) { return; }
        for (var grove : broken) {
            Chunk chunk = chunkOf(grove.coords());
            if (chunk == null) { continue; }
            for (Prop prop : grovesTrees(chunk, grove)) { treeSplit(prop.instance().model); }
        }
    }

    /** The tree model's fragments where its split is in, installed; else null, its split under way. */
    private KitFragments treeSplit(Model model) {
        var split = treeSplits.computeIfAbsent(model, KitFragments::splitLater);
        KitFragments done = split.isDone() && !split.isCompletedExceptionally() ? split.join() : null;
        if (done != null) { done.install(); }
        return done;
    }

    /**
     * The woods cover an attack took down (its checkpoint's, GpuBoardSource#publishScene: trees, crystals, a skin's rows)
     * breaks as its hit lands: each in its fragments (whole parts where its split is not in yet), they burst away from
     * {@code hit} and out (GpuDestruction#breakTrees) and sink once the terrain shows them gone.
     */
    void breakTrees(List<BoardScene.Detonation.Felled> broken, BoardScene.TileTransition.Hit hit) {
        if (coverScene == null || installedColumns || broken.isEmpty() || hit == null) { return; }
        var from = BoardGeometry.center(hit.from().coords(), hit.from().elevation());
        Set<Chunk> taken = new LinkedHashSet<>();
        installedSettings.run(() -> {
            for (var grove : broken) {
                Chunk chunk = breakGrove(grove, from, 0, true);
                if (chunk != null) { taken.add(chunk); }
            }
        });
        if (!taken.isEmpty()) {
            taken.forEach(this::refall);
            refreshShadows();
        }
        stampFalls();
    }

    /**
     * Breaks the hex's cover into pieces that burst away from {@code from}, {@code delay} playback seconds from now:
     * what draws on its own, and with {@code trees} its stand's trees too. The chunk that drew it, or null for none.
     */
    private Chunk breakGrove(BoardScene.Detonation.Felled grove, Vector3 from, float delay, boolean trees) {
        Coords at = grove.coords();
        Chunk chunk = chunkOf(at);
        if (chunk == null) { return null; }
        Array<Renderable> pieces = new Array<>();
        List<Matrix4> stood = new ArrayList<>();
        List<Prop> alone = new ArrayList<>();
        BoundingBox bounds = new BoundingBox().inf();
        Map<Material, Material> copies = new IdentityHashMap<>();
        for (Prop prop : grovesTrees(chunk, grove)) {
            if (prop.standing() && !trees) { continue; }
            ModelInstance drawn = prop.instance();
            KitFragments split = fragments ? treeSplit(drawn.model) : null;
            for (Node node : drawn.nodes) { addPieces(drawn, node, copies, pieces, split, true); }
            stood.add(new Matrix4(drawn.transform));
            if (!prop.standing()) { alone.add(prop); }
            bounds.ext(prop.bounds());
        }
        if (pieces.isEmpty()) { return null; }
        var middle = BoardGeometry.center(at, 0);
        var push = new Vector2(middle.x - from.x, middle.y - from.y);
        if (push.len2() < 1e-6f) { push.set(1, 0); }
        destruction.breakTrees(at, pieces, stood, Set.copyOf(grove.trees()), bounds, middle.x, middle.y, push.nor(), delay);
        broken.addAll(alone);
        return chunk;
    }

    /** How many hexes' trees a blast has felled, falling or lying. */
    int felledHexes() { return destruction.groveCount(); }

    /** Whether the hex's structure lies as pieces (falling, resting or sinking) instead of in its chunk. */
    boolean fallTaken(Coords at) { return destruction.taken(at); }

    /** Counts the changes of which hexes are taken (#fallTaken). */
    int fallsRevision() { return destruction.revision(); }

    /** Whether the hex's structure has begun to give way. */
    boolean fallStarted(Coords at) { return destruction.started(at); }

    /** Whether the kit levels drawn are still being broken into fragments for their falls (KitFragments): for tests. */
    boolean fragmentsPending() { return assets.fragmentsPending(); }

    /** The kits' installed fragments' bytes: for reports. */
    long fragmentBytes() { return assets.fragmentBytes(); }

    /** How many falling parts there are, and how many of them fall in fragments: for tests. */
    int[] fallParts() { return destruction.parts(); }

    /**
     * At most this many triangles of the structures one transition brings down are cut, shared among their hexes: a finer
     * level beyond a hex's share gives way to a coarser one.
     */
    private static final int COLLAPSE_TRIANGLES = 30_000;
    /** The pieces times the materials one hex breaks into, each a draw in the colour and shadow passes. */
    private static final int COLLAPSE_DRAWS = 360;
    /** The draws the hexes one transition brings down share (a nuclear blast's dozens), each at most COLLAPSE_DRAWS. */
    private static final int SHARED_COLLAPSE_DRAWS = 720;

    /**
     * The installed structure at the collapse's hex, read where its chunk draws it and starting to break: a building's,
     * fuel tank's or multi-hex share's shell, or a bridge span's deck, piers, footing and the rails and road it carries,
     * cut at their finest level within their share of {@link #COLLAPSE_TRIANGLES} in solid copies of their materials
     * (no fade, no hover cut, no glass, which shatters to nothing; a road's coats still blend over the deck), closed in
     * the building's own material or the bridge's concrete. It shares the budgets with the other {@code together - 1}
     * hexes its transition brings down. Null when nothing is drawn there.
     */
    private GpuCollapse collapse(BoardScene.TileTransition.Collapse change, int together) {
        Coords at = change.coords();
        BoardScene.Tile tile = coverScene.tile(at);
        if (tile == null) { return null; }
        TileMesh mesh = chunks.get(at.getX() / CHUNK_SIZE * chunkRows + at.getY() / CHUNK_SIZE).tileMeshes.get(at);
        if (mesh == null) { return null; }
        Chunk chunk = chunks.get(at.getX() / CHUNK_SIZE * chunkRows + at.getY() / CHUNK_SIZE);
        // The cut's frame lies at the hex's foot: its coordinates stay small however far out on the board it stands.
        var origin = BoardGeometry.center(at, tile.elevation());
        var frame = new Matrix4().setToTranslation(-origin.x, -origin.y, -origin.z);
        List<MeepleShatter.Source> sources = new ArrayList<>();
        Map<Material, Material> copies = new IdentityHashMap<>();
        boolean bridge = mesh.bridgeShape != null && BoardScene.TileTransition.structure(change.before())
              && change.before().cover().structure() == 0;
        // A rail, maglev beam, road or object the span carries, not one on the ground under it.
        float deck = BoardGeometry.surfaceZ(tile) + BoardGeometry.level() * .5f;
        for (Prop prop : mesh.props) {
            var placed = prop.decorationFeature == null ? null : prop.decorationFeature.decoration();
            boolean structure = prop.building != null || bridge && prop.bounds().min.z > deck
                        && (prop.route() || placed != null && megamek.common.board.RailRoute.onDeck(placed))
                  || !prop.floors && ("building".equals(prop.receiver) || "fuelTank".equals(prop.receiver));
            if (!structure || isFlag(prop)) { continue; }
            ModelInstance drawn = finest(prop, COLLAPSE_TRIANGLES / together);
            for (Node node : drawn.nodes) { addSources(node, drawn, frame, copies, sources); }
        }
        // A road's coats on the deck, in the chunk's overlay layer, sample masks that go with the chunk: the collapse
        // keeps copies of them (coatSource) and their meshes until the cut has read them.
        GpuTextures<BoardScene.Pixels> masks = null;
        List<Mesh> coatMeshes = new ArrayList<>();
        if (bridge) {
            Array<Renderable> solid = GpuTerrainDepth.snapshot(chunk.opaque), overlays = GpuTerrainDepth.snapshot(chunk.overlays);
            Map<TileRange, Material> coats = new LinkedHashMap<>();
            // Its landings and piers stand on as its ruin (BoardBridgeFooting#ruin): only the span falls.
            Standing standing = standing(tile, chunk.lod);
            for (TileRange range : mesh.ranges) {
                if (!range.route() || range.layer() != 0 && range.layer() != 2) { continue; }
                Material material = drawnMaterial(range.layer() == 0 ? solid : overlays, range);
                if (material == null) { continue; }
                if (range.layer() == 0) {
                    var span = falling(range, standing.triangles(), coatMeshes);
                    if (span != null) {
                        sources.add(new MeepleShatter.Source(span, copies.computeIfAbsent(material, GpuTerrain::pieceMaterial),
                              frame, 0));
                    }
                } else if (range.material().has(GpuRoads.Mask.TYPE)) {
                    coats.put(range, material);
                }
            }
            if (!coats.isEmpty()) {
                masks = new GpuTextures<>();
                Map<BoardScene.Pixels, BoardScene.Pixels> pixels = new HashMap<>();
                for (TileRange range : coats.keySet()) {
                    var data = range.material().get(GpuRoads.Mask.class, GpuRoads.Mask.TYPE).data.pixels();
                    pixels.put(data, data);
                }
                masks.update(pixels);
                Map<List<Object>, Material> coatMaterials = new HashMap<>();
                // The paint the deck carried falls with it: none on the ground under it, on its standing landings and
                // ramps, or on a bank its apron reaches.
                Predicate<Vector3[]> carried = corners -> {
                    float x = (corners[0].x + corners[1].x + corners[2].x) / 3;
                    float y = (corners[0].y + corners[1].y + corners[2].y) / 3;
                    var under = BoardGeometry.tile(coverScene, x, y);
                    return (corners[0].z + corners[1].z + corners[2].z) / 3 > deck && under != null && under.coords().equals(at)
                          && !standing.tops().contains(triangleKey(corners[0], corners[1], corners[2], false));
                };
                for (var coat : coats.entrySet()) {
                    var source = coatSource(coat.getKey(), coat.getValue(), masks, frame, coatMaterials, coatMeshes, carried);
                    if (source != null) { sources.add(source); }
                }
            }
        }
        GpuCollapse collapse = null;
        try {
            collapse = sources.isEmpty() ? null : collapse(change, sources, origin, bridge, masks,
                  Math.min(COLLAPSE_DRAWS, SHARED_COLLAPSE_DRAWS / together));
            return collapse;
        } finally {
            // The cut has read them; the masks are the collapse's, if there is one.
            coatMeshes.forEach(Mesh::dispose);
            if (collapse == null && masks != null) { masks.dispose(); }
        }
    }

    /**
     * What stands of a built bridge once its span falls (BoardBridgeFooting#ruin): its {@code triangles}
     * ({@link #triangleKey}), and in plan its {@code tops}, which keep the road's paint that lies on them.
     */
    private record Standing(Set<String> triangles, Set<String> tops) {
        static final Standing NONE = new Standing(Set.of(), Set.of());
    }

    /** What stands of the tile's built bridge once its span falls; nothing of a rock arch or a guideway. */
    private Standing standing(BoardScene.Tile tile, TerrainLod lod) {
        var deck = BoardBridge.deck(coverScene, tile);
        if (deck.natural() || BoardBridge.guideway(tile)) { return Standing.NONE; }
        Map<Coords, BoardSurface> surfaces = new HashMap<>();
        var ruin = BoardBridgeFooting.build(coverScene, tile, lod, surfaces, installedColumns)
              .ruin(coverScene, tile, deck, lod, surfaces, installedColumns);
        Set<String> triangles = new HashSet<>(), tops = new HashSet<>();
        for (var facet : ruin.facets()) {
            triangles.add(triangleKey(facet.a(), facet.b(), facet.c(), true));
            if (facet.part() == BoardBridge.Part.TOP) { tops.add(triangleKey(facet.a(), facet.b(), facet.c(), false)); }
        }
        return new Standing(triangles, tops);
    }

    /** A triangle by its corners, in any order, to a hundredth of a world unit; in plan without its {@code height}. */
    private static String triangleKey(Vector3 a, Vector3 b, Vector3 c, boolean height) {
        String[] corners = { corner(a, height), corner(b, height), corner(c, height) };
        Arrays.sort(corners);
        return String.join("|", corners);
    }

    private static String corner(Vector3 p, boolean height) {
        return Math.round(p.x * 100) + "," + Math.round(p.y * 100) + (height ? "," + Math.round(p.z * 100) : "");
    }

    /**
     * A range's vertices and indices copied out of its mesh: {@code points} filled, the indices returned, local to them.
     */
    private static com.badlogic.gdx.utils.ShortArray copied(TileRange range, com.badlogic.gdx.utils.FloatArray points) {
        var indices = new com.badlogic.gdx.utils.ShortArray();
        GpuPropBatch.copyVertices(range.mesh(), range.offset(), range.count(), points, indices);
        return indices;
    }

    /**
     * The triangles of a range's copy ({@code points} and {@code indices}, laid out as {@code attributes}) that
     * {@code keep} takes by their corners.
     */
    private static com.badlogic.gdx.utils.ShortArray kept(com.badlogic.gdx.utils.FloatArray points,
          com.badlogic.gdx.utils.ShortArray indices, VertexAttributes attributes, Predicate<Vector3[]> keep) {
        int stride = attributes.vertexSize / Float.BYTES, position = attributes.getOffset(VertexAttributes.Usage.Position);
        var kept = new com.badlogic.gdx.utils.ShortArray();
        Vector3[] corners = { new Vector3(), new Vector3(), new Vector3() };
        for (int i = 0; i + 2 < indices.size; i += 3) {
            for (int k = 0; k < 3; k++) {
                int at = indices.get(i + k) * stride + position;
                corners[k].set(points.get(at), points.get(at + 1), points.get(at + 2));
            }
            if (keep.test(corners)) { kept.add(indices.get(i), indices.get(i + 1), indices.get(i + 2)); }
        }
        return kept;
    }

    /**
     * A bridge range's triangles but those {@code standing} after its span falls, as a mesh of their own ({@code owned},
     * the caller's to dispose once the cut has read it); the range itself when nothing of it stands, null when all of it
     * does.
     */
    private static com.badlogic.gdx.graphics.g3d.model.MeshPart falling(TileRange range, Set<String> standing,
          List<Mesh> owned) {
        if (standing.isEmpty()) {
            return new com.badlogic.gdx.graphics.g3d.model.MeshPart("bridge", range.mesh(), range.offset(), range.count(),
                  GL20.GL_TRIANGLES);
        }
        var points = new com.badlogic.gdx.utils.FloatArray();
        var indices = copied(range, points);
        var attributes = range.mesh().getVertexAttributes();
        int stride = attributes.vertexSize / Float.BYTES;
        var kept = kept(points, indices, attributes,
              corners -> !standing.contains(triangleKey(corners[0], corners[1], corners[2], true)));
        if (kept.isEmpty()) { return null; }
        if (kept.size == indices.size) {
            return new com.badlogic.gdx.graphics.g3d.model.MeshPart("bridge", range.mesh(), range.offset(), range.count(),
                  GL20.GL_TRIANGLES);
        }
        var copy = new Mesh(Mesh.VertexDataType.VertexArray, false, points.size / stride, kept.size, attributes);
        copy.setVertices(points.toArray());
        copy.setIndices(kept.toArray());
        owned.add(copy);
        return new com.badlogic.gdx.graphics.g3d.model.MeshPart("bridge", copy, 0, kept.size, GL20.GL_TRIANGLES);
    }

    /**
     * The material {@code drawn}, a chunk layer's parts, draws {@code range} with: the chunk's own copy, as it finished
     * it. Null when none draws it.
     */
    private static Material drawnMaterial(Array<Renderable> drawn, TileRange range) {
        for (Renderable part : drawn) {
            var mesh = part.meshPart;
            if (mesh.mesh == range.mesh() && mesh.offset <= range.offset() && range.offset() < mesh.offset + mesh.size) {
                return part.material;
            }
        }
        return null;
    }

    /**
     * A road coat the span carries, as a source of its cut, null for none: its triangles that {@code carried} takes,
     * copied out of the chunk's overlay mesh into a
     * mesh of their own ({@code owned}, the caller's to dispose once the cut has read it), their mask moved into
     * {@code masks}, which outlive the chunk's, and the material the chunk draws it with ({@code drawn}) on those masks,
     * one for each such material and page ({@code materials}). It still blends over the deck: the pieces draw it after
     * themselves (GpuCollapse#renderOverlays).
     */
    private static MeepleShatter.Source coatSource(TileRange range, Material drawn, GpuTextures<BoardScene.Pixels> masks,
          Matrix4 frame, Map<List<Object>, Material> materials, List<Mesh> owned, Predicate<Vector3[]> carried) {
        var mask = range.material().get(GpuRoads.Mask.class, GpuRoads.Mask.TYPE);
        var region = masks.region(mask.data.pixels());
        var points = new com.badlogic.gdx.utils.FloatArray();
        var indices = kept(points, copied(range, points), range.mesh().getVertexAttributes(), carried);
        if (indices.isEmpty()) { return null; }
        float[] vertices = points.toArray();
        var attributes = range.mesh().getVertexAttributes();
        GpuRoads.relocate(vertices, new GpuRoads.Mask(region, mask.data), attributes);
        var copy = new Mesh(Mesh.VertexDataType.VertexArray, false, vertices.length / (attributes.vertexSize / Float.BYTES),
              indices.size, attributes);
        copy.setVertices(vertices);
        copy.setIndices(indices.toArray());
        owned.add(copy);
        Texture page = region.getTexture();
        Texture chunkPage = mask.textureDescription.texture;
        Material material = materials.computeIfAbsent(List.of(drawn, page), key -> {
            var moved = new Material(drawn);
            // The chunk's mask page also stands in as a merged coat's diffuse texture, which gives it its coordinates.
            for (Attribute attribute : drawn) {
                if (attribute instanceof TextureAttribute texture && texture.textureDescription.texture == chunkPage) {
                    var onPage = (TextureAttribute) texture.copy();
                    onPage.textureDescription.texture = page;
                    moved.set(onPage);
                }
            }
            return moved;
        });
        return new MeepleShatter.Source(new com.badlogic.gdx.graphics.g3d.model.MeshPart("coat", copy, 0, indices.size,
              GL20.GL_TRIANGLES), material, frame, 0);
    }

    /**
     * The collapse of {@code change}'s structure, read as {@code sources}, in about {@code draws} draws; it owns
     * {@code masks}, which may be null.
     */
    private GpuCollapse collapse(BoardScene.TileTransition.Collapse change, List<MeepleShatter.Source> sources,
          Vector3 origin, boolean bridge, GpuTextures<BoardScene.Pixels> masks, int draws) {
        MeepleShatter.Caps caps = bridge ? new MeepleShatter.Caps(material(assets.material("terrain/surfaces/concrete-ground"), false),
              MeshBatch.STANDARD, BoardGeometry.width() / 5 * assets.coverage("terrain/surfaces/concrete-ground"))
              : buildingCaps(sources);
        // Their programs link before the terrain installs the hex without the structure (awaitingLinks), as an edit's do.
        for (var source : sources) { prepareTerrainMaterial(source.material(), source.part().mesh.getVertexAttributes()); }
        prepareTerrainMaterial(caps.material(), caps.attributes());
        var bounds = new BoundingBox().inf();
        for (var source : sources) {
            var box = new BoundingBox();
            source.part().mesh.calculateBoundingBox(box, source.part().offset, source.part().size);
            bounds.ext(box.mul(source.transform()));
        }
        bounds.mul(new Matrix4().setToTranslation(origin));
        float storey = BoardGeometry.level();
        int levels = bridge ? 0 : MathUtils.clamp(Math.round(bounds.getDepth() / storey) - 1, 0, 3);
        int channels = (int) sources.stream().map(MeepleShatter.Source::material).distinct().count() + 1;
        int pieces = MathUtils.clamp(draws / channels, draws < COLLAPSE_DRAWS ? 4 : 10, bridge ? 20 : 48);
        // Steep breaks per storey band: s of them cross a band into about 1 + s + s^2 / 4 pieces.
        int steep = MathUtils.clamp(MathUtils.ceil(2 * ((float) Math.sqrt((float) pieces / (levels + 1)) - 1)), 2, 7);
        var base = bridge ? bounds.getCenter(new Vector3()) : new Vector3(origin.x, origin.y, bounds.min.z);
        var loads = change.loads().stream().map(load -> BoardGeometry.center(load.coords(), load.elevation())).toList();
        var from = change.from() == null ? null : BoardGeometry.center(change.from().coords(), change.from().elevation());
        var fall = new MeepleDebris.Collapse(base, storey, bridge, from, change.strength(), loads);
        boolean water = change.before().liquid().present() && !change.before().frozen();
        return new GpuCollapse(sources, origin, MeepleShatter.Planes.crumble(levels, steep), caps, fall,
              Math.max(bounds.getWidth(), bounds.getHeight()) * .5f, bounds.getDepth(), water, change.coords().hashCode(),
              masks);
    }

    /** The prop's model at its finest level within {@code triangles}, else its coarsest. */
    private static ModelInstance finest(Prop prop, int triangles) {
        ModelInstance level = prop.near();
        for (int lod = 1; lod < prop.lodCount() && triangles(level) > triangles; lod++) { level = prop.level(lod, prop.instance()); }
        return level;
    }

    private static int triangles(ModelInstance instance) {
        int count = 0;
        for (Renderable part : GpuTerrainDepth.snapshot(List.of(instance))) { count += part.meshPart.size / 3; }
        return count;
    }

    /** The drawn parts of {@code node} and its children, as sources of the cut in its frame. */
    private static void addSources(Node node, ModelInstance instance, Matrix4 frame, Map<Material, Material> copies,
          List<MeepleShatter.Source> sources) {
        for (NodePart part : node.parts) {
            // Glass is what blends in the model itself: a building faded around its occupants or the pointer still breaks.
            Material own = instance.model.getMaterial(part.material.id);
            if (!part.enabled || part.meshPart.primitiveType != GL20.GL_TRIANGLES || part.meshPart.size == 0
                  || blended(own != null ? own : part.material)) { continue; }
            var boardUV = part.material.get(GpuModelMaterial.class, GpuModelMaterial.TYPE);
            sources.add(new MeepleShatter.Source(part.meshPart, copies.computeIfAbsent(part.material,
                        GpuTerrain::pieceMaterial),
                  new Matrix4(frame).mul(instance.transform).mul(node.globalTransform), boardUV == null ? 0 : boardUV.boardUV));
        }
        for (Node child : node.getChildren()) { addSources(child, instance, frame, copies, sources); }
    }

    /**
     * A piece's copy of a drawn material: solid, whatever fade its building wore; drawn from both sides, as
     * a shell open below (a kit storey's floor belongs to its interior) shows its inside when its foot topples over; and
     * on its mesh's UVs, which carry its board UVs once cut.
     */
    private static Material pieceMaterial(Material drawn) {
        var copy = new Material(drawn);
        opaque(copy);
        copy.set(IntAttribute.createCullFace(GL20.GL_NONE));
        var surface = copy.get(GpuModelMaterial.class, GpuModelMaterial.TYPE);
        if (surface != null && surface.boardUV > 0) { copy.set(surface.ownUVs()); }
        return copy;
    }

    /**
     * Where a building's cuts close: in its structural body ({@link GeneratedMesh#BUILDING_BODY}) where it has one, else in
     * the material most of it is made of, each cut's face textured across its plane a storey to a repeat.
     */
    private static MeepleShatter.Caps buildingCaps(List<MeepleShatter.Source> sources) {
        Map<Material, Integer> sizes = new IdentityHashMap<>();
        for (var source : sources) { sizes.merge(source.material(), source.part().size, Integer::sum); }
        Material body = MeepleShatter.Caps.body(sources.stream().map(MeepleShatter.Source::material).toList(), sizes::get);
        var layout = sources.stream().filter(source -> source.material() == body).reduce((first, last) -> last).orElseThrow();
        return new MeepleShatter.Caps(body, layout.part().mesh.getVertexAttributes(), BoardGeometry.level());
    }

    /** Pieces whose structure the terrain now shows gone take its place, from the frame it does. */
    private void startCollapses() {
        for (Collapsing fall : collapsing) {
            var tile = coverScene.tile(fall.coords());
            if (!fall.collapse().started() && (tile == null || !BoardScene.TileTransition.structure(tile))) {
                fall.collapse().start();
            }
        }
    }

    /**
     * The falling pieces, drawn with the terrain's programs as the structures they were; a road's coats on them draw
     * later, over them (GpuCollapse#renderOverlays).
     */
    private void renderCollapses(ModelBatch target, Environment lighting) {
        for (Collapsing fall : collapsing) {
            var instance = fall.collapse().instance();
            if (instance != null) { target.render(instance, lighting); }
        }
    }

    /** A collapse's pieces are still being cut: the terrain keeps its structure installed until they are. */
    private boolean cutting() {
        return collapsing.stream().anyMatch(fall -> !fall.collapse().ready());
    }

    /**
     * Runs the collapses' pieces on by {@code seconds} of playback time under {@code gravity} standard gravities,
     * landing on {@code ground}, and lets go of those that have sunk away.
     */
    void advanceCollapses(float seconds, MeepleDebris.Ground ground, float gravity) {
        collapsing.removeIf(fall -> {
            fall.collapse().advance(seconds, ownGround(fall.coords(), ground), gravity);
            boolean done = fall.collapse().done();
            if (done) { fall.collapse().dispose(); }
            // Its pieces down, the rubble under them rises (#takeRubble).
            if (done || fall.collapse().settled()) { destruction.landed(fall.coords()); }
            return done;
        });
    }

    /**
     * Where a collapse's pieces land: on {@code ground}, but in a bridge's own hex on its floor (its bed under water),
     * past the landings and piers its ruin keeps standing, which the deck rested on and its pieces start inside.
     */
    private MeepleDebris.Ground ownGround(Coords at, MeepleDebris.Ground ground) {
        return (x, y, from, own) -> {
            var tile = coverScene == null ? null : BoardGeometry.tile(coverScene, x, y);
            if (tile == null || !tile.coords().equals(at) || BoardBridge.feature(tile) == null) { return ground.height(x, y, from, own); }
            boolean liquid = tile.liquid().present() && !tile.frozen();
            return (liquid ? BoardGeometry.groundZ(tile) : BoardGeometry.surfaceZ(tile)) + .5f;
        };
    }

    /** The collapses under way, cut pieces. */
    List<GpuCollapse> collapses() {
        return collapsing.stream().map(Collapsing::collapse).toList();
    }

    /** The dust of every structure coming down, which the attack effects draw: cut pieces' and falling kits'. */
    List<java.util.function.Consumer<GpuExplosionEffects>> dust() {
        List<java.util.function.Consumer<GpuExplosionEffects>> result = new ArrayList<>();
        int count = collapsing.size();
        for (Collapsing fall : collapsing) { result.add(effects -> fall.collapse().explosions(effects, count)); }
        if (!destruction.isEmpty()) { result.add(destruction::dust); }
        return result;
    }

    /** Ends every collapse at once, as Instant playback does: the terrain shows what replaced them. */
    void clearCollapses() {
        disposeCollapses();
        fallsFor = null;
        // What the installed scene still shows standing (a strike's buildings the rules bring down at the phase's end, or
        // its trees) goes back into its chunk.
        Set<Chunk> standing = new LinkedHashSet<>();
        for (Coords at : destruction.taken()) {
            int index = at.getX() / CHUNK_SIZE * chunkRows + at.getY() / CHUNK_SIZE;
            if (coverScene != null && index < chunks.size()) { standing.add(chunks.get(index)); }
        }
        destruction.clear();
        if (!standing.isEmpty()) {
            standing.forEach(this::refall);
            refreshShadows();
        }
        stampFalls();
    }

    private void disposeCollapses() {
        collapsing.forEach(fall -> fall.collapse().dispose());
        collapsing.clear();
    }

    private boolean heights(BoardScene.TileTransition transition, java.util.function.Function<BoardScene.TileTransition.Change,
          BoardScene.Tile> shown) {
        return coverScene == null || transition.changes().stream().allMatch(change -> {
            var tile = coverScene.tile(change.coords());
            return tile == null || tile.elevation() == shown.apply(change).elevation();
        });
    }

    /**
     * How far a unit's height may lie from a roof's for it to stand on that roof: drawn on it ({@link #roofLift}) and
     * not inside the building under it (the occupancy cutaway in {@link #animate}), in levels.
     */
    private static final float ROOF_REACH = .25f;

    /**
     * How far over its game height a unit at {@code position} is drawn so that it stands on the roof under it: on a kit
     * building's roof as drawn, its own surface (GpuBuilding.Assembly#roof: a vault, a raised deck), never its parapets,
     * fittings or lights. Presentation only: the unit plays at its roof's level. 0 off a roof, on a roof that marks no
     * surface, and on an authored building, whose roof for a unit is its volumes' top, the game's height. It fades out
     * as the unit leaves the roof's level (climbing, jumping), so nothing jumps.
     */
    float roofLift(BoardScene scene, Vector3 position) {
        BoardScene.Tile tile = BoardGeometry.tile(scene, position.x, position.y);
        if (tile == null || tile.cover().structure() == 0 || tile.cover().height() <= 0) { return 0; }
        float roof = (tile.elevation() + tile.cover().height()) * BoardGeometry.level();
        float near = 1 - Math.abs(position.z - roof) / (ROOF_REACH * BoardGeometry.level());
        Coords at = tile.coords();
        int index = at.getX() / CHUNK_SIZE * chunkRows + at.getY() / CHUNK_SIZE;
        if (near <= 0 || index >= chunks.size()) { return 0; }
        TileMesh mesh = chunks.get(index).tileMeshes.get(at);
        if (mesh == null) { return 0; }
        var hit = new Vector3();
        float aside = .3f * BoardGeometry.hexScale();
        for (Prop prop : mesh.props) {
            if (prop.building == null || prop.supportTriangles != null || prop.floors) { continue; }
            Matrix4 inverse = new Matrix4(prop.instance().transform).inv();
            // A hair aside where the roof's pieces meet under the unit, as hitProp looks.
            for (float[] offset : SEAM_ASIDE) {
                Ray local = new Ray(new Vector3(position.x + offset[0] * aside, position.y + offset[1] * aside, prop.bounds().max.z + 1)
                      .mul(inverse), new Vector3(0, 0, -1).rot(inverse));
                if (prop.building.roof(local, hit)) {
                    // A roof rises half a level at the most over its game height (BayKit.Vault).
                    return near * MathUtils.clamp(hit.mul(prop.instance().transform).z - roof, 0, BoardGeometry.level() / 2);
                }
            }
        }
        return 0;
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

        void renderOverlays(ModelBatch target, Environment lighting) {
            for (Top top : tops()) {
                renderRetained(top.overlays(), target, lighting);
                renderRetained(top.paint(), target, lighting);
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
            if (source != null && BoardRoad.rendered(source)) { return true; }
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
              // A coat on a water hex carries its water's palette (GpuRoads#underWater).
              || !coats.isEmpty() && !before.liquid().equals(after.liquid())
              || IntStream.range(0, coats.size()).anyMatch(i -> !sameCoat(at, oldCoats.get(i), coats.get(i)))
              || !sameBridge(was.infrastructure().bridgeShapes().get(at), shown.infrastructure().bridgeShapes().get(at))) {
            return false;
        }
        if (features.isEmpty()) { return true; }
        for (var feature : features) {
            if (feature.kind() == BoardScene.FeatureKind.BUILDING
                  && (before.cover().interiors() != after.cover().interiors()
                        || HexAppearance.steppedBuilding(before.appearance()) != HexAppearance.steppedBuilding(after.appearance())
                        || !Arrays.equals(ConnectedKit.reach(was.scene(), before, Math.round(feature.height())),
                              ConnectedKit.reach(shown.scene(), after, Math.round(feature.height())))
                        || !Arrays.equals(ConnectedKit.corners(was.scene(), before), ConnectedKit.corners(shown.scene(), after))
                        || !KitDoors.cuts(was.scene(), before, feature).equals(KitDoors.cuts(shown.scene(), after, feature)))) {
                return false;
            }
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
              infrastructure.roads(), infrastructure.bridges(), infrastructure.bridgeShapes(), Map.of(), Set.of(), Map.of(), false, infrastructure.authored(),
              Map.of());
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
            if (!drawn(prop, shadows) || prop.floors && (shadows || !chunk.interiorFloorVisible(prop))) { continue; }
            if (shadows || !chunk.faded.contains(prop)) { target.render(prop.instance(), lighting); }
        }
        if (!shadows) {
            for (InteriorStruts strut : chunk.struts) {
                if (!chunk.hiddenInteriors.contains(strut.coords) && chunk.interiorVisible(strut.coords)) {
                    target.render(strut.instance, lighting);
                }
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
                        Coords owner = feature.kind() == BoardScene.FeatureKind.BUILDING && GpuAuthoredBuilding.supports(feature)
                              ? tile.cover().reference().anchor() : tile.coords();
                        result.put(feature.decoration().id(), new EditorPlacement(owner, feature));
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
            List<EditorObject> result = new ArrayList<>(editorProps(coords).stream().filter(prop -> prop.decorationId != null && prop.decorationOwner == null)
                  .map(prop -> new EditorObject(prop.decorationId, new BoundingBox(prop.bounds()),
                        new OrientedBoundingBox(prop.editorBounds, prop.instance().transform), prop.anchorZ() / BoardGeometry.level(),
                        new Vector3(prop.instance().transform.val[Matrix4.M03], prop.instance().transform.val[Matrix4.M13], prop.anchorZ()),
                        (prop.previewFeature == null ? prop.decorationFeature : prop.previewFeature).decoration().placement(),
                        drawn(prop, false), false))
                  .toList());
            Map<String, List<Prop>> structures = new LinkedHashMap<>();
            for (Chunk chunk : chunks) {
                for (Prop prop : chunk.props) {
                    if (coords.equals(prop.owner())) { structures.computeIfAbsent(prop.decorationId, ignored -> new ArrayList<>()).add(prop); }
                }
            }
            structures.forEach((id, shares) -> {
                Prop first = shares.getFirst();
                var shown = first.previewFeature == null ? first.decorationFeature : first.previewFeature;
                var object = shown.decoration();
                BoundingBox box = new BoundingBox().inf();
                shares.forEach(share -> box.ext(share.bounds()));
                BoundingBox local = new BoundingBox().inf();
                shares.forEach(share -> local.ext(share.editorBounds));
                float unit = BoardGeometry.hexScale();
                var outline = new OrientedBoundingBox(local, new Matrix4().scale(unit, unit, unit)
                      .mul(GpuAuthoredBuilding.frame(coords, object, shown.elevation())));
                float z = shown.elevation();
                Vector3 anchor = new Vector3(BoardGeometry.centerX(coords) + (float) object.x() * BoardGeometry.width(),
                      BoardGeometry.centerY(coords) + (float) object.y() * BoardGeometry.height(), z * BoardGeometry.level());
                result.add(new EditorObject(id, box, outline, z, anchor, object.placement(), shares.stream().anyMatch(share -> drawn(share, false)), false));
            });
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
            // Use the same projected basis as the paint, including tilt, mirror and stretch. V runs down the image;
            // local Y runs up. The box remains on the receiving surface instead of becoming a floating tilted quad.
            var uv = BoardDecals.paintUv(object);
            Matrix4 toLocal = new Matrix4();
            toLocal.val[Matrix4.M00] = (float) uv.ux(); toLocal.val[Matrix4.M01] = (float) uv.uy();
            toLocal.val[Matrix4.M10] = (float) -uv.vx(); toLocal.val[Matrix4.M11] = (float) -uv.vy();
            toLocal.translate(-x, -y, 0);
            BoundingBox local = new BoundingBox().inf();
            Vector3 localPoint = new Vector3();
            float z = Float.NEGATIVE_INFINITY;
            for (Coords recipient : BoardDecals.footprint(coords, object, coverScene.width(), coverScene.height())) {
                TileMesh mesh = editorTile(recipient);
                if (mesh == null) { continue; }
                for (PaintedDecal paint : mesh.paint) {
                    if (!paint.stamp().owner().equals(coords) || !paint.stamp().object().id().equals(object.id())) { continue; }
                    for (var face : paint.faces()) {
                        for (Vector3 corner : List.of(face.a(), face.b(), face.c())) {
                            box.ext(corner.x, corner.y, corner.z + paint.lift());
                            local.ext(localPoint.set(corner.x, corner.y, corner.z + paint.lift()).mul(toLocal));
                        }
                        z = Math.max(z, face.height(x, y));
                    }
                }
            }
            if (box.min.x > box.max.x) { continue; }
            if (!Float.isFinite(z)) { z = box.min.z; }
            result.add(new EditorObject(object.id(), box, new OrientedBoundingBox(local, toLocal.inv()), z / BoardGeometry.level(), new Vector3(x, y, z), object.placement(),
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
            List<ModelInstance> objects = new ArrayList<>();
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
                    if (prop.floors || !isolate.isEmpty() && !isolate.equals(prop.decorationId)) { continue; }
                    // A close-up: a building or object with mesh levels draws its near one, whatever the board's zoom.
                    var near = prop.near();
                    batch.render(near, environment);
                    objects.add(near);
                }
            } finally { batch.end(); }
            BoundingBox bounds = chunks.get(coords.getX() / CHUNK_SIZE * chunkRows + coords.getY() / CHUNK_SIZE).bounds;
            sectionFade.renderWithGlass(batch, camera, environment, isolate.isEmpty() ? tile.glass : List.of(),
                  () -> sectionFade.render(batch, camera, environment, objects, bounds));
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
            if (prop.floors) { continue; }
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
                        // Authored meshes already contain their base height; their instance translates only in XY.
                        float anchorZ = prop.decorationOwner == null ? prop.instance().transform.val[Matrix4.M23]
                              : (prop.previewFeature == null ? prop.decorationFeature : prop.previewFeature).elevation() * BoardGeometry.level();
                        result = new DecorationHit(prop.decorationOwner == null ? prop.coords() : prop.owner(), prop.decorationId, nearest, anchorZ);
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
                      && !"bridge".equals(prop.receiver) && !prop.floors) { continue; }
                // What the zoom hides is looked through. What the Tactical View hides is still picked (shown).
                if ((prop.tree() || prop.industrial) && !includeFoliage || !shown(prop, false)) { continue; }
                // A structure giving way is no longer there to pick: its pieces fall where the pointer looks through.
                if (prop.building != null && destruction.started(prop.coords())) { continue; }
                if ((placement || walkable ? hitSupport(prop, ray, hit) : hitProp(prop, ray, hit))) {
                    float distance = ray.origin.dst2(hit);
                    if (distance < nearest) {
                        nearest = distance;
                        result = prop.coords();
                        hardSurface = prop.hardSurface;
                        receiver = prop.receiver != null ? prop.receiver
                              : prop.floors ? "building" : "ground";
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

    /** Authored helpers supply standing surfaces; visual antennas and dishes cannot become roofs. */
    private boolean hitSupport(Prop prop, Ray ray, Vector3 hit) {
        if (prop.supportTriangles == null) { return hitProp(prop, ray, hit); }
        if (prop.supportTriangles.isEmpty()) { return false; }
        Matrix4 inverse = new Matrix4(prop.instance().transform).inv();
        Ray local = new Ray(new Vector3(ray.origin).mul(inverse), new Vector3(ray.direction).rot(inverse));
        if (!Intersector.intersectRayTriangles(local, prop.supportTriangles, hit)) { return false; }
        hit.mul(prop.instance().transform); return true;
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
                  : isFlag(prop) ? Intersector.intersectRayTriangles(local, triangles(prop.instance().model), hit)
                  : Intersector.intersectRayTriangles(local, pickingTriangles(prop, local), hit);
            if (found) {
                hit.mul(prop.instance().transform);
                return true;
            }
        }
        return false;
    }

    /**
     * The prop's picking triangles {@code local}, its ray in model space, can meet: for a vertical ray, as falling pieces
     * and placement cast them, only those over its point (TriangleGrid), so an authored building share's thousands are not
     * all tested for every piece, every step.
     */
    private List<Vector3> pickingTriangles(Prop prop, Ray local) {
        if (prop.pickingTriangles != null) { return prop.pickingTriangles; }
        var triangles = featureTriangles.computeIfAbsent(prop.pickingModel, GpuTerrain::triangles);
        if (triangles.size() < TriangleGrid.LEAST * 3 || Math.abs(local.direction.x) > 1e-6f
              || Math.abs(local.direction.y) > 1e-6f) { return triangles; }
        return featureGrids.computeIfAbsent(prop.pickingModel, model -> TriangleGrid.of(triangles))
              .at(local.origin.x, local.origin.y);
    }

    /**
     * Triangles bucketed by their extent seen from above: a vertical ray at (x, y) meets only those of its cell, in their
     * order, and so finds the same nearest hit as against them all.
     */
    private record TriangleGrid(float minX, float minY, float cell, int columns, int rows, List<List<Vector3>> cells) {
        /** Fewer triangles than this are tested as they are. */
        static final int LEAST = 256;
        /** Cells along the longer side. */
        private static final int SIDE = 24;

        static TriangleGrid of(List<Vector3> triangles) {
            var bounds = new BoundingBox().inf();
            triangles.forEach(bounds::ext);
            float cell = Math.max(Math.max(bounds.getWidth(), bounds.getHeight()) / SIDE, 1e-3f);
            int columns = (int) Math.floor(bounds.getWidth() / cell) + 1, rows = (int) Math.floor(bounds.getHeight() / cell) + 1;
            List<List<Vector3>> cells = new ArrayList<>(columns * rows);
            for (int i = 0; i < columns * rows; i++) { cells.add(new ArrayList<>()); }
            for (int i = 0; i + 2 < triangles.size(); i += 3) {
                Vector3 a = triangles.get(i), b = triangles.get(i + 1), c = triangles.get(i + 2);
                int c0 = (int) Math.floor((Math.min(a.x, Math.min(b.x, c.x)) - bounds.min.x) / cell);
                int c1 = (int) Math.floor((Math.max(a.x, Math.max(b.x, c.x)) - bounds.min.x) / cell);
                int r0 = (int) Math.floor((Math.min(a.y, Math.min(b.y, c.y)) - bounds.min.y) / cell);
                int r1 = (int) Math.floor((Math.max(a.y, Math.max(b.y, c.y)) - bounds.min.y) / cell);
                for (int row = Math.max(0, r0); row <= Math.min(rows - 1, r1); row++) {
                    for (int column = Math.max(0, c0); column <= Math.min(columns - 1, c1); column++) {
                        var list = cells.get(row * columns + column);
                        list.add(a); list.add(b); list.add(c);
                    }
                }
            }
            return new TriangleGrid(bounds.min.x, bounds.min.y, cell, columns, rows, cells);
        }

        List<Vector3> at(float x, float y) {
            int column = (int) Math.floor((x - minX) / cell), row = (int) Math.floor((y - minY) / cell);
            return column < 0 || row < 0 || column >= columns || row >= rows ? List.of() : cells.get(row * columns + column);
        }
    }

    /** Where {@link #hitProp} casts again, in turn, when its ray finds nothing: the ray itself first. */
    private static final float[][] SEAM_ASIDE = { { 0, 0 }, { .6f, .8f }, { -.8f, .6f }, { -.6f, -.8f }, { .8f, -.6f } };

    static List<Vector3> triangles(Model model) { return triangles(model.nodes, part -> true); }

    static List<Vector3> triangles(Node node) { return triangles(List.of(node), part -> true); }

    /** The triangles of {@code nodes}' enabled parts that {@code parts} accepts, in model space. */
    static List<Vector3> triangles(Iterable<Node> nodes, java.util.function.Predicate<NodePart> parts) {
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
     * The Tactical View's draw rule, with no rebuild: scatter, grass and biome vegetation hide; everything else draws as
     * in every view. Only the shadow map is redrawn.
     */
    void setScatterHidden(boolean hidden) {
        if (scatterHidden == hidden) { return; }
        scatterHidden = hidden;
        shadowMap.invalidate();
    }

    boolean scatterHidden() { return scatterHidden; }

    /** Native checks: the twin draws of the last depth pre-pass. */
    int prepassDraws() { return prepassDraws; }

    /**
     * Native checks: whether a prop, terrain or top page the last frame showed has a merge still to make. A merge changes
     * the draw order, so where surfaces tie in depth, a few pixels change when it lands. Scar and paint pages are left
     * out: they advance after the opaque flush, past the frame's page deadline whenever that flush outlasts it.
     */
    boolean pagesMerging() {
        return propBatch.merging() || terrainPages.merging() || topPages.merging() || levelsPending;
    }

    /** In render's colour stage: whether a depth pre-pass drew, so the scene depth holds its surfaces. */
    boolean prepassed() { return prepassed; }

    /** The opaque batch's render context, for a pass in render's colour stage (GpuAmbientOcclusion). */
    RenderContext renderContext() { return batch.getRenderContext(); }

    /** Native checks: each generic colour program with a depth twin, and the twin. */
    Map<Shader, Shader> prepassTwins() { return Collections.unmodifiableMap(prepassTwins); }

    private void recacheProps(Chunk chunk) {
        chunk.levelsPacked = levelFrame;
        scarSurfaceRevision++;
        GpuPropBatch.disposeMeshes(chunk.shadowPropRenderables);
        chunk.shadowPropRenderables.clear();
        refall(chunk);
    }

    /**
     * Snapshots the chunk's props without the structures that lie as pieces (GpuDestruction), stamped with the falls'
     * revision: a chunk kept at another detail level is snapshotted again when it comes back (replaceDetail).
     */
    private void refall(Chunk chunk) {
        chunk.cacheProps(destruction::taken);
        // Its stand leaves felled trees out: it is gathered again only when the falls changed since.
        if (chunk.fallsRevision != destruction.revision()) { chunk.restand(destruction::felled); }
        chunk.fallsRevision = destruction.revision();
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
     * Whether the ground material cache draws this terrain's level tops. Prints and previews borrow its assets and
     * draw directly. The cache stores only plain authored finishes; live effects are applied afterward.
     */
    private boolean cachesGround() {
        return GpuMaterialCache.ENABLED && !borrowedAssets && plainSculptFinish();
    }

    /**
     * All authored sculpt finishes lack emission (map or glow), metallic and scattering. Their nonnegative map means
     * are zero only where every texel is zero. The direct shader can discard those unused channels before live effects;
     * volcanic programs retain their procedural emission. This reads the sculpt arrays already owned by the assets.
     */
    private boolean plainSculptFinish() {
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
            materialCache.chunk(chunkIndex, chunk.topBounds,
                  camera.frustum.boundsInFrustum(chunk.topBounds), chunk.cacheSince);
            if (chunk.wallBounds.isValid()) {
                materialCache.walls(chunkIndex, chunk.charts,
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

    private void updateBuildingLights() {
        lavaLighting.setBuildings(chunks.stream().flatMap(chunk -> chunk.props.stream())
              .flatMap(prop -> prop.drawnLights.stream()).toList());
    }

    private void render(Camera camera, Runnable prepassStage, Runnable colourStage, boolean view) {
        if (view) { flagView = camera; }
        // A changed lighting configuration returns every surface to its generic program before anything draws.
        if (familyPrograms.configure(environment)) {
            terrainBatch.clear();
            startFamilySession();
        }
        // Cosmetic relief layers are Enhanced-only. Routes belong to both graphics modes.
        boolean terrain = !installedColumns;
        // Upload before the render context tracks bound texture units for this pass, and before the fill reads the
        // fields' (cachedField); Classic's ice reads the biomes' too.
        biomes.update(coverScene);
        // The ground material cache fills the pages this view needs before anything draws (its own passes). Its
        // programs draw only when the warm-up linked every program of this lighting, the direct ones included.
        materialCache.begin(camera, terrain && materialsReady(), view, this::drawCacheTops);
        cacheChunks(camera);
        materialCache.end();
        shadingPass++;
        updateDetail(camera);
        packLevels();
        lavaLighting.update(coverScene, terrain, 1 - environmentLighting.daylight());
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
            boolean scatterVisible = terrain && !scatterHidden && visible && chunk.scatterVisible;
            Hidden hidden = hidden(chunk);
            boolean packed = !hidden.props();
            // The 3D relief, or the Classic columns, in the chunk's opaque layer.
            int page = terrainPage(chunkIndex);
            terrainPages.add(chunk.terrainRenderables, page, visible && hidden.ground() == null);
            topPages.add(chunk.topRenderables, page, chunkIndex, visible && hidden.ground() == null);
            if (visible && hidden.ground() != null) { renderRetained(hidden.ground(), batch, environment); }
            int propPage = propPage(chunkIndex);
            // Hover/occupancy changes only this chunk's presentation, not its static page membership.
            // Keep the complete opaque source cached and draw the opened chunk separately.
            propBatch.add(chunk.shadowProps, propPage, visible && packed && chunk.faded.isEmpty());
            if (visible && packed && !chunk.faded.isEmpty()) { propBatch.addDynamic(chunk.solidProps); }
            propBatch.add(chunk.scatterRenderables, propPage, scatterVisible);
            propBatch.add(chunk.routeRenderables, propPage, visible && packed);
            if (visible && !packed) { renderRetainedProps(chunk, batch, environment, false); }
            if (terrain) {
                trees.add(chunk.stand, visible);
                trees.add(chunk.scatterStand, scatterVisible);
            }
            if (!visible) {
                continue;
            }
            if (packed) {
                if (chunk.sharedPlacements == null) { chunk.sharedPlacements = trees.placements(chunk.sharedProps); }
                trees.add(chunk.sharedPlacements);
                addDecorations(chunk, batch, trees, environment, false, false);
            }
        }
        if (lifts != null) { lifts.render(batch, environment); }
        renderCollapses(batch, environment);
        if (!destruction.isEmpty()) { batch.render(destruction.pieces(GpuTreeInstances.Pass.COLOUR), environment); }
        // Trees first: the opaque sorter keeps this order of shaders, so the ground hidden under crowns fails the depth
        // test instead of being shaded and painted over.
        batch.render(trees, environment);
        // One share of the frame for every page merge: props, terrain, scars and paint (GpuMeshPage.BUILD_NANOS).
        long pageDeadline = System.nanoTime() + GpuMeshPage.BUILD_NANOS;
        propBatch.render(batch, environment, pageDeadline);
        terrainPages.render(batch, environment, pageDeadline);
        // Each chunk's covered tops/walls take their cached twins; equal adjacent ranges still share one draw.
        topPages.render(batch, environment, pageDeadline, materialCache::material);
        // The depth pre-pass: hidden colour fragments then fail the depth test before they shade. Not on the Classic columns.
        // The gather, the draw order and both flushes stay as they were.
        if (prepassStage != null) { prepassStage.run(); }
        prepassed = terrain && renderPrepass(camera);
        try {
            if (colourStage != null) { colourStage.run(); }
            // Opaque ground cover first, over the pre-pass's depth: the ground it hides then fails the depth test instead
            // of being shaded and painted over, as under tree crowns.
            if (terrain && !scatterHidden) { renderCover(camera); }
            batch.end();
        } finally {
            // Its last draw may have tested EQUAL: passes that enable depth testing directly find the usual LEQUAL.
            if (prepassed) { Gdx.gl.glDepthFunc(GL20.GL_LEQUAL); }
            prepassed = false;
        }
        batch.begin(camera);
        if (terrain && combatScars) {
            surfaceScars.render(camera, batch, environment, coverScene.height(), this::scarShown, pageDeadline);
        }
        paintPages.begin(camera);
        for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
            Chunk chunk = chunks.get(chunkIndex);
            boolean visible = camera.frustum.boundsInFrustum(chunk.bounds);
            if (visible) {
                // A chunk holds the decals of one graphics only: Enhanced paint, or the Classic tileset decals.
                Array<Renderable> overlays = hidden(chunk).overlays();
                if (overlays != null) { renderRetained(overlays, batch, environment); }
                else { chunk.overlays.forEach(instance -> batch.render(instance, environment)); }
                chunk.surfaceDecals.forEach(instance -> batch.render(instance, environment));
                chunk.tilesetDecals.forEach(instance -> batch.render(instance, environment));
            }
            if (chunk.paintRenderables == null) { chunk.paintRenderables = GpuTerrainDepth.snapshot(chunk.authoredPaint); }
            Array<Renderable> paint = hidden(chunk).paint();
            paintPages.add(chunk.paintRenderables, terrainPage(chunkIndex), visible && paint == null);
            if (visible && paint != null) { renderRetained(paint, batch, environment); }
        }
        if (lifts != null) { lifts.renderOverlays(batch, environment); }
        for (Collapsing fall : collapsing) { fall.collapse().renderOverlays(batch, environment); }
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
        if (depthPrepass.isEmpty() && environmentLighting.ambientOcclusion == null
              && !environmentLighting.cheaperShading) {
            return false;
        }
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
        // Every frame presents a new scene over the same tiles.
        List<BoardScene.Tile> tiles = coverScene == null ? null : coverScene.tiles();
        if (fieldsTiles != tiles) {
            fieldsTiles = tiles;
            fields = tiles != null && tiles.stream().anyMatch(tile -> BoardBiome.kind(tile) == BoardScene.Biome.FIELD);
        }
        return fields;
    }

    /**
     * Ground cover in its own batch (coverBatch), over the pre-pass's depth and before the terrain's colour: adding grass
     * to the colour batch would end its material/page batching.
     */
    private void renderCover(Camera camera) {
        if (coverScene == null) { return; }
        boolean grassShown = grass && BoardQuality.current.meadowShare > 0;
        boolean coverVisible = GpuGroundCover.visibleAtScale(camera, grassShown, fields());
        // At a scale where no cover draws, no hexes are gathered; the empty list still releases the plants' buffers.
        coverChunks.clear();
        if (coverVisible || GpuBiomeVegetation.visibleAtScale(camera)) {
            float margin = BoardGeometry.width() * 2;
            for (int index = 0; index < chunks.size(); index++) {
                coverGuard.set(chunks.get(index).bounds);
                coverGuard.min.add(-margin, -margin, 0);
                coverGuard.max.add(margin, margin, 0);
                coverGuard.update();
                if (camera.frustum.boundsInFrustum(coverGuard)) { coverChunks.set(index); }
            }
        }
        if (coverTiles != coverScene.tiles() || !coverChunks.equals(coverShown)) {
            coverTiles = coverScene.tiles();
            coverShown.clear();
            coverShown.or(coverChunks);
            List<BoardScene.Tile> tiles = new ArrayList<>();
            for (int index = coverChunks.nextSetBit(0); index >= 0; index = coverChunks.nextSetBit(index + 1)) {
                chunkTiles(coverScene, index, tiles);
            }
            coverCandidates = List.copyOf(tiles);
        }
        List<BoardScene.Tile> candidates = coverCandidates;
        coverBatch.begin(camera);
        if (coverVisible) {
            groundCover.showGrass(grassShown);
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
        return trees.bytes() + shadowTrees.bytes();
    }

    /** Tree/building instance buffer uploads so far; see {@link GpuTreeInstances#uploads()}. */
    long treeInstanceUploads() {
        return trees.uploads() + shadowTrees.uploads();
    }

    /** Water and faded features follow units, with depth testing but no depth writes. */
    void renderTransparent(Camera camera) {
        shadingPass++;
        waterDepth.invalidate();
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
                translucent(chunk);
                for (Prop prop : chunk.faded) { if (drawn(prop, false)) { batch.render(prop.instance(), environment); } }
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
            renderOverWater(camera);
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
        renderOverWater(camera);
    }

    /**
     * What lies over the water, which no view ray above the ground reaches before them: cutouts' faded texels and the
     * glass, each in its true order, then the scenery's effects.
     */
    private void renderOverWater(Camera camera) {
        List<ModelInstance> panes = new ArrayList<>();
        for (Chunk chunk : chunks) {
            if (!chunk.glass.isEmpty() && camera.frustum.boundsInFrustum(chunk.bounds)) { panes.addAll(chunk.glass); }
        }
        cutoutFade.renderWithGlass(batch, camera, environment, panes, () -> renderFades(camera));
        renderSceneryEffects(camera);
    }

    /** The faded texels of cutouts, plants and fences alike, over the finished scene (GpuCutoutFade), as #render draws the solid ones. */
    private void renderFades(Camera camera) {
        boolean terrain = !installedColumns;
        batch.begin(camera);
        trees.begin(GpuTreeInstances.Pass.FADE);
        fadeBounds.inf();
        for (Chunk chunk : chunks) {
            boolean visible = camera.frustum.boundsInFrustum(chunk.bounds);
            boolean scatterVisible = terrain && !scatterHidden && visible && chunk.scatterVisible;
            if (terrain) {
                trees.add(chunk.stand, visible);
                trees.add(chunk.scatterStand, scatterVisible);
            }
            if (!visible) { continue; }
            fadeBounds.ext(chunk.bounds);
            for (int i = 0; i < chunk.fadeParts.size; i++) {
                Prop owner = chunk.fadeOwners.get(i);
                // A cutout's texels go only with a building faded away fully (#fade).
                if (!drawn(owner, false) || chunk.faded.contains(owner) && buildingOpacity == 0
                      || owner.scatter && !scatterVisible) { continue; }
                Renderable part = chunk.fadeParts.get(i);
                if (GpuTreeInstances.instanceable(part.worldTransform) && instancedShader(part.material)) {
                    trees.add(part);
                } else {
                    part.shader = null;
                    part.environment = environment;
                    batch.render(part);
                }
            }
        }
        batch.render(trees, environment);
        // Felled trees keep the faded texels they had standing (GpuDestruction).
        if (destruction.groveCount() > 0) {
            batch.render(destruction.pieces(GpuTreeInstances.Pass.FADE), environment);
            fadeBounds.ext(destruction.bounds(new BoundingBox()));
        }
        if (batch.gathered().isEmpty()) { batch.end(); }
        else { cutoutFade.flush(batch, camera, fadeBounds); }
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

    /** The level to draw at. The cache's fills take no lighting: their material stays as it is whatever the level. */
    void setQuality(BoardQuality quality) {
        // Detail levels follow its hex sizes (TerrainLod.select), the same across levels sharing a shading: a still camera
        // selects again. Each terrain notices for itself: an embedded view shares the level but not this one's selection.
        if (environmentLighting.cheaperShading != quality.cheaperShading) { detailPixelsPerUnit = Float.NaN; }
        BoardQuality.current = quality;
        assets.setTerrainAnisotropy(quality.terrainAnisotropy);
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
     * Whether this pass draws this prop: what the zoom shows ({@link #shown}). No packed prop is small: only placed
     * objects are, and they are never packed.
     */
    /**
     * Rubble the rules left where a structure fell, which its chunk does not draw while its hex's fall lasts: it rises
     * out of the ground once the pieces are down (#takeRubble). Its shadow stays in the cached map, under the pile.
     */
    private final Set<Prop> rising = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    /** Objects that stood on a falling structure (a helipad, roof fittings): they fall with it, as its pieces. */
    private final Set<Prop> carried = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    /** Woods cover that draws on its own (crystals, a skin's rows), broken into flying pieces while they last (#breakGrove). */
    private final Set<Prop> broken = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    private boolean drawn(Prop prop, boolean shadows) {
        if (!carried.isEmpty() && carried.contains(prop) && destruction.taken(prop.coords)) { return false; }
        if (!broken.isEmpty() && broken.contains(prop) && destruction.felled(prop.instance().transform)) { return false; }
        if (!shadows && !rising.isEmpty() && rising.contains(prop) && destruction.taken(prop.coords)) { return false; }
        if (prop.floors && chunks.get(prop.coords.getX() / CHUNK_SIZE * chunkRows
              + prop.coords.getY() / CHUNK_SIZE).hiddenInteriors.contains(prop.coords)) { return false; }
        return !replaced(prop) && shown(prop, shadows);
    }

    private void translucent(Chunk chunk) {
        for (int i = 0; i < chunk.translucentParts.size; i++) {
            Prop owner = chunk.translucentOwners.get(i);
            if (!drawn(owner, false) || chunk.faded.contains(owner)) { continue; }
            Renderable part = chunk.translucentParts.get(i);
            if (GpuCrystalTransmission.ruby(part.material)) { continue; }
            part.shader = null;
            part.environment = environment;
            batch.render(part);
        }
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
     * outside the shadow pass so are faded objects, which {@link #renderTransparent} draws.
     * <p>
     * KEEP IN SYNC with the packed caches of buildings and scenery in the same passes: Chunk#solidProps and #shadowProps
     * (#render, #renderDepth, #renderOutlineDepth). Placed objects take the same fades (Chunk#cacheProps with omitFaded)
     * and shadows that ignore fading; only they hide by size ({@link #drawn}).
     */
    private void addDecorations(Chunk chunk, ModelBatch target, GpuTreeInstances instances, Environment environment,
          boolean shadows, boolean sunlight) {
        if (shadows) {
            if (!sunlight) { translucentGeometry(chunk, target, true); }
        } else {
            // Cloudy ruby transports light inside its body, but its visible boundary writes solid camera depth.
            // Intergrown crystals cannot be correctly ordered as whole alpha-blended prisms.
            for (int i = 0; i < chunk.translucentParts.size; i++) {
                Renderable part = chunk.translucentParts.get(i);
                if (!GpuCrystalTransmission.ruby(part.material) || !drawn(chunk.translucentOwners.get(i), false)) { continue; }
                part.shader = null;
                part.environment = environment;
                target.render(part);
            }
        }
        for (int i = 0; i < chunk.decorationRenderables.size; i++) {
            Prop owner = chunk.decorationOwners.get(i);
            if (drawn(owner, shadows) && (shadows || !chunk.faded.contains(owner))) { instances.add(chunk.decorationRenderables.get(i)); }
        }
        for (int i = 0; i < chunk.looseDecorations.size; i++) {
            Prop owner = chunk.looseOwners.get(i);
            if (!drawn(owner, shadows) || shadows && isFlag(owner) || !shadows && chunk.faded.contains(owner)) { continue; }
            Renderable part = chunk.looseDecorations.get(i);
            part.shader = null;
            part.environment = environment;
            target.render(part);
        }
    }

    /**
     * Actual boundaries for outlines and wireframe. The sun's map takes none: glass lets the sun through, like the
     * multi-hex buildings' glass ({@link BoardMultiHexBuilding#glass}), and ruby transmits it in its own pass.
     */
    private void translucentGeometry(Chunk chunk, ModelBatch target, boolean shadows) {
        for (int i = 0; i < chunk.translucentSolids.size; i++) {
            if (!drawn(chunk.translucentOwners.get(i), shadows)) { continue; }
            Renderable part = chunk.translucentSolids.get(i);
            part.shader = null;
            part.environment = null;
            target.render(part);
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

    private static boolean isFlag(Prop prop) {
        return prop.decorationFeature != null
              && BoardFlag.ASSET.equals(prop.decorationFeature.asset());
    }

    void setWind(BoardAtmosphere.Effects effects) {
        double angle = Math.toRadians(effects.windDirection());
        wind.set((float) Math.sin(angle), (float) Math.cos(angle), effects.wind());
    }

    private void chunkTiles(BoardScene scene, int index, List<BoardScene.Tile> destination) {
        int startX = (index / chunkRows) * CHUNK_SIZE;
        int startY = (index % chunkRows) * CHUNK_SIZE;
        for (int x = startX; x < Math.min(startX + CHUNK_SIZE, scene.width()); x++) {
            for (int y = startY; y < Math.min(startY + CHUNK_SIZE, scene.height()); y++) {
                destination.add(scene.tile(x, y));
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

    /** Faded buildings that still cover units for their outlines; one faded away fully is gone. */
    boolean hasFadedBuildings() {
        return buildingOpacity > 0 && chunks.stream().anyMatch(chunk -> !chunk.faded.isEmpty());
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
                addDecorations(chunk, depthBatch, trees, null, true, false);
                trees.add(chunk.sharedShadows);
            }
        }
        depthBatch.render(trees);
        if (!destruction.isEmpty()) { depthBatch.render(destruction.pieces(GpuTreeInstances.Pass.DEPTH)); }
        depthBatch.end();
    }

    /** Camera depth retains the cutaway so atmosphere effects do not hide units behind faded surfaces. */
    void renderDepth(Camera camera, List<ModelInstance> units, ModelBatch pass) {
        updateDetail(camera);
        renderDepth(camera, units, pass, false, true);
    }

    /**
     * Wireframe fill and lines share the camera's cutaway and detail selection, without scatter terrain. No colour pass
     * draws with them, so the chunks that wait for their levels' pack take it here: by the second of a frame's two
     * draws at the latest (packLevels).
     */
    void renderWireframe(Camera camera, ModelBatch pass) {
        updateDetail(camera);
        packLevels();
        renderDepth(camera, List.of(), pass, false, false);
    }

    private void renderDepth(Camera camera, List<ModelInstance> units, ModelBatch pass, boolean shadows,
          boolean includeScatter) {
        pass.begin(camera);
        try {
            GpuTreeInstances instances = shadows ? shadowTrees : trees;
            instances.begin(shadows ? GpuTreeInstances.Pass.SHADOW : GpuTreeInstances.Pass.DEPTH);
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
                boolean scatterVisible = includeScatter && !scatterHidden && visible && chunk.scatterVisible
                      && (!shadows || chunk.scatterDiameter * texels >= 2);
                if (shadows) {
                    int level = Math.max(TreeLod.level(chunk.treeDiameter * texels, 0), coarsest);
                    instances.add(chunk.stand, level, visible);
                    instances.add(chunk.scatterStand, level, scatterVisible);
                } else {
                    instances.add(chunk.stand, visible);
                    instances.add(chunk.scatterStand, scatterVisible);
                }
                if (visible) {
                    Hidden hidden = hidden(chunk);
                    if (hidden.ground() != null) { renderRetained(hidden.ground(), pass, null); }
                    else { pass.render(shadows ? chunk.depthTerrain.shadows : chunk.depthTerrain); }
                    if (scatterVisible) {
                        chunk.scatter.forEach(pass::render);
                    }
                    if (hidden.props()) { renderRetainedProps(chunk, pass, null, shadows); }
                    else {
                        // A shadow ignores fading, unless a building has faded away fully.
                        boolean wholeShadows = shadows && buildingOpacity > 0;
                        pass.render(wholeShadows ? chunk.shadowProps : chunk.solidProps);
                        addDecorations(chunk, pass, instances, null, shadows, shadows);
                        pass.render(chunk.routes);
                        // By batch, gathered once a snapshot: part by part, a city's modules took most of a shadow
                        // redraw and of every frame's depth pass.
                        if (wholeShadows) {
                            if (chunk.shadowPlacements == null) {
                                chunk.shadowPlacements = shadowTrees.placements(chunk.sharedShadows);
                            }
                            instances.add(chunk.shadowPlacements);
                        } else if (shadows) {
                            instances.add(chunk.sharedProps);
                        } else {
                            if (chunk.sharedPlacements == null) {
                                chunk.sharedPlacements = trees.placements(chunk.sharedProps);
                            }
                            instances.add(chunk.sharedPlacements);
                        }
                    }
                    if (!includeScatter) { translucentGeometry(chunk, pass, false); }
                }
            }
            pass.render(instances);
            if (!shadows && includeScatter && !destruction.isEmpty()) { pass.render(destruction.pieces(GpuTreeInstances.Pass.DEPTH)); }
            if (lifts != null && !shadows) { lifts.render(pass, null); }
            if (!shadows) { renderCollapses(pass, null); }
            renderDepthUnits(camera, units, pass);
        } finally { pass.end(); }
    }

    private void renderDepthUnits(Camera camera, List<ModelInstance> units, ModelBatch pass) {
        for (ModelInstance unit : units) {
            if (unit == fallCaster) { pass.render(destruction.pieces(GpuTreeInstances.Pass.SHADOW)); }
            else if (camera.frustum.boundsInFrustum(unitBounds(unit))) { GpuUnitInstance.renderDepth(pass, unit); }
        }
    }

    /**
     * The falling pieces among the shadow map's units: drawn over its cached terrain like a unit, again whenever their
     * clock has moved on (revision), within the bounds their structures stood in, a hex wider all round.
     */
    private final ModelInstance fallCaster = new ModelInstance(new Model());

    void renderShadows(List<ModelInstance> units) {
        renderShadows(null, units);
    }

    void renderShadows(Camera view, List<ModelInstance> units) {
        if (view != null) { updateDetail(view); }
        if (lifts != null || !flagRevisions.isEmpty() || !collapsing.isEmpty() || !destruction.isEmpty()) {
            // Moving lifts and falling pieces cast like units, over the cached terrain's shadow, which leaves them out.
            units = new ArrayList<>(units);
            if (!destruction.isEmpty()) { units.add(fallCaster); }
            if (lifts != null) { units.addAll(lifts.models); }
            for (Collapsing fall : collapsing) {
                if (fall.collapse().instance() != null) { units.add(fall.collapse().instance()); }
            }
            for (Chunk chunk : chunks) {
                for (Prop prop : chunk.flags()) {
                    if (drawn(prop, true)) { units.add(prop.instance()); }
                }
            }
        }
        shadowMap.render(view, units, shadowBounds, environment.shadowMap != null,
              atmosphere == null || atmosphere.hasDirectLight());
        if (environmentLighting.crystals != null) {
            var parts = new ArrayList<Renderable>();
            for (Chunk chunk : chunks) {
                for (int i = 0; i < chunk.translucentParts.size; i++) {
                    Renderable part = chunk.translucentParts.get(i);
                    if (GpuCrystalTransmission.ruby(part.material) && drawn(chunk.translucentOwners.get(i), true)) { parts.add(part); }
                }
            }
            crystalTransmission.update(environment.shadowMap instanceof GpuShadowLight light
                  && (atmosphere == null || atmosphere.hasDirectLight()) ? light : null, shadowMap.revision(), parts);
        }
    }


    /** All passes use the viewing camera's detail selection; scatter culling never rebuilds a mesh. */
    private void updateDetail(Camera camera) {
        boolean perspective = camera.projection.val[Matrix4.M33] == 0;
        float pixelsPerUnit = perspective ? Float.NaN : BoardCamera.pixelsPerUnit(camera);
        if (pixelsPerUnit == detailPixelsPerUnit) {
            // The trees drop the levels they dissolved from once the last dissolve has ended.
            if (treesSettle > 0 && (clock >= treesSettle || !treeDissolves)) {
                treesSettle = 0;
                for (Chunk chunk : chunks) { detailTrees(chunk, pixelsPerUnit); }
            }
            return;
        }
        detailPixelsPerUnit = pixelsPerUnit;
        treesSettle = 0;
        // Small placed objects hide while a hex is under TreeLod.SMALL_OBJECTS pixels; perspective views keep them, like
        // grass. A crossing alone does not redraw the shadow map: the objects and their shadows are at most a few
        // pixels, and a forced redraw would drop the cached board-wide shadow on every zoom-out.
        smallHidden = smallObjects && !perspective && TreeLod.level(TreeLod.SMALL_OBJECTS,
              BoardGeometry.width() * pixelsPerUnit, smallHidden ? 1 : 0) == 1;
        // Further out, building shells' twins cost more than the shading they save; the twins stay built.
        objectsFar = !perspective && TreeLod.level(TreeLod.PREPASS_OBJECTS, BoardGeometry.width() * pixelsPerUnit,
              objectsFar ? 1 : 0) == 1;
        reliefFar = perspective || TreeLod.level(TreeLod.PREPASS_RELIEF, BoardGeometry.width() * pixelsPerUnit,
              reliefFar ? 1 : 0) == 1;
        Vector3 nearest = new Vector3();
        for (Chunk chunk : chunks) {
            if (perspective) {
                // A chunk uses its nearest possible depth, preserving detail for all of its trees and scatter.
                pixelsPerUnit = BoardCamera.pixelsPerUnit(camera, nearest(camera, chunk.bounds, nearest));
            }
            float scatterPixels = chunk.scatterDiameter * pixelsPerUnit;
            // Scatter this small casts a shadow under a texel: its appearance waits for the next refit.
            chunk.scatterVisible = scatterPixels >= (chunk.scatterVisible ? 2 : 3);
            boolean packedLevels = false, objectLevels = false;
            chunk.levelScale = pixelsPerUnit;
            for (Prop prop : chunk.props) {
                int level = prop.lod;
                if (prop.detail(pixelsPerUnit)) {
                    if (prop.decorationFeature != null || prop.translucent) { objectLevels = true; }
                    else {
                        packedLevels = true;
                        if (prop.lod < level && !chunk.levelsFiner) {
                            chunk.levelsFiner = true;
                            chunk.finerScale = pixelsPerUnit;
                        }
                    }
                    // A kit level drawn now breaks ahead of its fall too (KitFragments).
                    if (prop.building != null && fragments) { prop.building.kit().cutFragments(prop.instance().model); }
                }
            }
            // Packed props (buildings, scenery) repack the chunk's prop caches, which also gather its placed objects
            // again: at once, unless the chunk packed a level change within the last LEVEL_WAIT frames (packLevels).
            // Placed objects alone only gather their instanced parts again. The rebuilt caches serve the next shadow
            // refit; an outline is the same at every level, so the map is not redrawn for the change alone (200-400 ms
            // per redraw on an Intel Iris Xe when the fit spans MesaCity 1, and its 233 building hexes change level
            // throughout a tilt).
            if (packedLevels && chunk.levelsDue(levelFrame)) {
                recacheProps(chunk);
            } else if (packedLevels || objectLevels) {
                scarSurfaceRevision++;
                chunk.cacheDecorations();
                if (packedLevels) { chunk.levelsStale = chunk.levelsChanged = levelsPending = true; }
            }
            detailTrees(chunk, pixelsPerUnit);
        }
        // Felled trees take the scale at their own nearest depth, as a chunk's do.
        if (perspective && destruction.groveCount() > 0) {
            pixelsPerUnit = BoardCamera.pixelsPerUnit(camera, nearest(camera, destruction.bounds(new BoundingBox()), nearest));
        }
        destruction.detail(pixelsPerUnit);
    }

    /** Frames between a chunk's repacks while its props keep changing level ({@link #packLevels}). */
    private static final int LEVEL_WAIT = 30;
    /**
     * How much a prop that waits for a finer level may grow on screen before its chunk repacks anyway: the coarse mesh
     * shows meanwhile, whatever the zoom's speed or the frame rate. A coarser level only waits with more triangles.
     */
    private static final float LEVEL_GROWTH = 1.25f;
    /** The frames drawn, as {@link #packLevels} counts them. */
    private long levelFrame;
    /** Some chunk's packed props left the levels its caches hold ({@link Chunk#levelsStale}). */
    private boolean levelsPending;

    /**
     * Once a frame, after its detail selection. A chunk repacks its prop caches the moment a packed prop changes level
     * (updateDetail); a pack copies every packed prop of the chunk (24 ms for 64 hexes of rubble, strongpoints and
     * timber on an RTX 4070), and in a zoom that keeps crossing some prop's threshold (props of many sizes) that was
     * every frame. A chunk that packed a level change within the last {@link #LEVEL_WAIT} frames therefore waits
     * here, drawing the levels it packed last: until the first frame its props kept their levels, until those frames
     * have passed, or until a prop waiting for a finer level has grown {@link #LEVEL_GROWTH} on screen
     * ({@link Chunk#levelsDue}); one such chunk a frame.
     */
    private void packLevels() {
        levelFrame++;
        if (!levelsPending) { return; }
        levelsPending = false;
        boolean waited = false;
        for (Chunk chunk : chunks) {
            if (!chunk.levelsStale) { continue; }
            if (!chunk.levelsChanged || chunk.levelsDue(levelFrame) && !waited) {
                waited |= chunk.levelsChanged;
                recacheProps(chunk);
            } else {
                levelsPending = true;
            }
            chunk.levelsChanged = false;
        }
    }

    /** The corner of {@code box} nearest along the camera's view, where a perspective view chooses its detail. */
    private static Vector3 nearest(Camera camera, BoundingBox box, Vector3 out) {
        return out.set(camera.direction.x >= 0 ? box.min.x : box.max.x, camera.direction.y >= 0 ? box.min.y : box.max.y,
              camera.direction.z >= 0 ? box.min.z : box.max.z);
    }

    /**
     * Each tree takes the level its own size asks, dissolving into it in a live view (Stand#detail). The shadow map
     * chooses its own levels from its texel (renderDepth).
     */
    private void detailTrees(Chunk chunk, float pixelsPerUnit) {
        float dissolve = treeDissolves ? TreeLod.DISSOLVE : 0;
        treesSettle = Math.max(treesSettle, Math.max(chunk.stand.detail(pixelsPerUnit, clock, dissolve),
              chunk.scatterStand.detail(pixelsPerUnit, clock, dissolve)));
    }

    /**
     * For tests: each model file a prop outside the tree stands draws from, and whether one of its props draws the
     * file's near mesh in the last view. Where every prop is small on screen, a file that holds simpler levels and
     * still draws its near mesh is drawn by a path that never takes them.
     */
    Map<java.io.File, Boolean> propFiles() {
        Map<java.io.File, Boolean> files = new HashMap<>();
        for (Chunk chunk : chunks) {
            for (Prop prop : chunk.props) {
                java.io.File file = prop.standTree() ? null : assets.modelFile(prop.levels.getFirst());
                if (file != null) { files.merge(file, prop.instance.model == prop.levels.getFirst(), Boolean::logicalOr); }
            }
        }
        return files;
    }

    /** Whether the board has water, where a falling unit splashes (GpuWaterImpacts). */
    boolean hasWater() { return chunks.stream().anyMatch(chunk -> !chunk.water.isEmpty()); }

    /**
     * Links the spore and geyser programs now (GpuBattleView's warm-up) on a board that has them, not when the first
     * comes into view; in the editor, which a stroke may give them, the geysers' and the wave simulations' always: they
     * link in milliseconds, and a stroke's first water must not hold a frame.
     */
    void prepareEffects() {
        if (chunks.stream().anyMatch(chunk -> !chunk.spores.isEmpty())) { fungus.prepare(); }
        if (editable || chunks.stream().anyMatch(chunk -> !chunk.geysers.isEmpty())) { geysers.prepare(); }
        if (editable) {
            ocean.prepare();
            lavaOcean.prepare();
        }
    }

    @Override
    public void dispose() {
        if (lifts != null) { lifts.dispose(); lifts = null; }
        disposeCollapses();
        destruction.clear();
        treeSplits.values().forEach(split -> {
            split.cancel(false);
            split.thenAccept(done -> { if (done != null) { done.dispose(); } });
        });
        treeSplits.clear();
        meshGeneration++;
        detailWorker.shutdownNow();
        // Workers borrow chunk/asset data. ForkJoinPool.close may return once termination has started after
        // shutdownNow; wait for actual termination before freeing those resources, even if closing is interrupted.
        boolean interrupted = false;
        while (!detailWorker.isTerminated()) {
            try { detailWorker.awaitTermination(1, TimeUnit.DAYS); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) { Thread.currentThread().interrupt(); }
        familyPrograms.dispose();
        if (terrainMaterialPreparation != null) { terrainMaterialPreparation.dispose(); }
        terrainMaterialSamples.forEach(sample -> sample.meshPart.mesh.dispose());
        terrainMaterialSamples.clear();
        retiredChunks.forEach(Chunk::dispose);
        retiredChunks.clear();
        if (rebuild != null) { rebuild.replacements.values().forEach(Chunk::dispose); }
        for (DetailJob job : pending) { discard(job); }
        pending.clear();
        rebuild = null;
        requested = null;
        if (detailJob != null) { discard(detailJob); }
        detailJob = null;
        detailCache.values().forEach(Chunk::dispose);
        detailCache.clear();
        terrainBatch.clear();
        groundCover.dispose();
        biomeVegetation.dispose();
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
        shadowTrees.dispose();
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
        crystalTransmission.dispose();
        lavaLighting.dispose();
        destruction.dispose();
        depthBatch.dispose();
        shadowMap.dispose();
        if (borrowedAssets) { assets.release(this); } else { assets.dispose(); }
        rainNoise.dispose();
        surfaceNoise.dispose();
        waterDepth.dispose();
        cutoutFade.dispose();
        sectionFade.dispose();
        ocean.dispose();
        waterExposure.dispose();
        lavaOcean.dispose();
        featureTriangles.clear();
        featureGrids.clear();
    }
}
