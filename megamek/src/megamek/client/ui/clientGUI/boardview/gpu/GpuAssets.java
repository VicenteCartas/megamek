/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.GLTexture;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureArray;
import com.badlogic.gdx.graphics.TextureArrayData;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Disposable;
import megamek.common.board.BoardBillboard;
import megamek.common.board.BoardFlag;
import megamek.common.Configuration;
import megamek.common.board.TilesetScenery;
import megamek.common.board.BoardDecoration;

/** GL-thread ownership of shared models, repeating terrain materials, and animated liquid frames. */
final class GpuAssets implements Disposable {
    private static final megamek.logging.MMLogger LOGGER = megamek.logging.MMLogger.create(GpuAssets.class);
    /** The marked box's model and material id: what a missing or unreadable model draws as, in every view. */
    static final String MISSING = "missing-asset";
    private final File root = new File(Configuration.dataDir(), "models/board");
    /** Board materials: Godot 4 StandardMaterial3D resources and their maps (GodotMaterial), with materials.json. */
    private final File textures = new File(root, "textures");
    private final Map<String, GodotMaterial> definitions = new HashMap<>();
    private final Map<String, Float> coverages = new HashMap<>();
    private GpuMaterialCatalog catalog;
    private final Map<String, Model> models = new HashMap<>();
    private final Map<String, List<Model>> modelLods = new HashMap<>();
    /** Each model file's levels in its own colours, as a prop draws them ({@link #levels(String)}). */
    private final Map<String, List<Model>> plainLevels = new HashMap<>();
    /** Each recoloured model's decoded levels, in the model's own colours (a missing level repeats the one before). */
    private final Map<String, List<ModelData>> recolourable = new HashMap<>();
    /** Models' levels decoding on the common pool ahead of their first use ({@link #prepareModels}); each taken once. */
    private final Map<String, CompletableFuture<List<ModelData>>> decoding = new HashMap<>();
    /** The models that could not load, each with why: each draws as the marked box ({@link #missing}), logged once. */
    private final Map<String, String> unloadable = new HashMap<>();
    /** Those drawn as boxes since the last report ({@link #takeMissing}), each with why. */
    private final Map<String, String> unreported = new java.util.LinkedHashMap<>();
    /** Those already reported for the current board ({@link #newBoard}): a graphics switch does not list them again. */
    private final java.util.Set<String> reported = new java.util.HashSet<>();
    /** Whether each placed model has colour slots, read from its file's JSON once. */
    private final Map<String, Boolean> hasSlots = new HashMap<>();
    /** The file each model made of a model file's mesh came from ({@link #fileModel}). */
    private final Map<Model, File> modelFiles = new java.util.IdentityHashMap<>();
    /** Each placed model's own box ({@link #bounds}); a model no longer referenced drops out. */
    private final Map<Model, BoundingBox> bounds = new java.util.WeakHashMap<>();
    /** Whether each model, kit or paint file exists, asked once ({@link #isFile}). */
    private final Map<File, Boolean> files = new HashMap<>();
    private final Map<Interior, Model> interiors = new HashMap<>();
    // By kit: every kit file and generated footprint is read once (ConnectedKit#kit, HabitatKit#kit).
    private final Map<ConnectedKit.Kit, GpuBuilding> buildings = new java.util.IdentityHashMap<>();
    private final Map<BoardIndustrial.Layout, Model> industrial = new HashMap<>();
    /** Each share building share's levels and glass (BoardMultiHexBuilding), retired with the other generated models. */
    private record MultiHex(List<Model> levels, Model glass) { }
    private final Map<BoardMultiHexBuilding.Layout, MultiHex> multiHexes = new HashMap<>();
    private final Map<GpuAuthoredBuilding.Layout, GpuAuthoredBuilding.Slice> authoredBuildings = new HashMap<>();

    GpuAuthoredBuilding.Slice authoredBuilding(GpuAuthoredBuilding.Prepared prepared) {
        return authoredBuildings.computeIfAbsent(prepared.layout(), key -> GpuAuthoredBuilding.build(prepared, this::createModel));
    }
    private final Map<BoardRail.Layout, Model> routes = new HashMap<>();
    private final Map<String, Texture> materials = new HashMap<>();
    private final Map<String, Cliff> cliffs = new HashMap<>();
    private LavaEmission lavaEmission;
    private final Map<String, Sculpt> sculpts = new HashMap<>();
    private TerrainArrays terrainArrays;
    private MaterialArrays fullSizeMaps;
    private float[] roadRelief;
    private MaterialArrays roadArray;
    private final Map<BoardLiquid.Textures, Animation<Texture>> liquids = new HashMap<>();
    private BoardScene.Pixels incline;
    private BoardScene.Pixels highIncline;

    private record Interior(Model shell, int levels) { }

    /** Three aligned, repeating maps. Surface channels are height, roughness, occlusion and relief range. */
    record Cliff(Texture color, Texture normal, Texture surface) { }

    /** A board material's Godot 4 Standard definition, read once per asset owner (asset reload makes a fresh owner). */
    GodotMaterial definition(String name) {
        return definitions.computeIfAbsent(name, key -> {
            File file = new File(textures, key + ".tres");
            requireFiles(List.of(new FileHandle(file)));
            return GodotMaterial.read(file, textures);
        });
    }

    boolean hasMaterial(String name) { return new File(textures, name + ".tres").isFile(); }

    GpuMaterialCatalog catalog() {
        if (catalog == null) { catalog = GpuMaterialCatalog.read(new File(textures, "materials.json")); }
        return catalog;
    }

    /**
     * How many times its catalogued ground a loose material's texture covers: 1 at its catalogued width, 2 at twice
     * that width. Every renderer that repeats the material in world space multiplies its repeat by this.
     */
    float coverage(String name) {
        return coverages.computeIfAbsent(name, key -> {
            int[] size = GpuMaterialMaps.size(definition(key).albedo());
            return catalog().coverage(key, size[0], size[1]);
        });
    }

    /** The road repeat in metres: {@link BoardRelief#detailMetres}(1), one hex width (30 m) over 5. */
    private static final float ROAD_REPEAT_METRES = 30f / 5;

    /**
     * How many road repeats one repeat of a road material covers: its catalogued {@code metres} in road repeats, or
     * one without them, widened with its texture ({@link #coverage}). Its road, bridge deck and tunnel floor multiply
     * the road repeat by this.
     */
    float roadCoverage(String name) {
        float metres = catalog().loose(name).metres();
        return coverage(name) * (Float.isNaN(metres) ? 1 : metres / ROAD_REPEAT_METRES);
    }

    /**
     * How many road repeats one repeat of a sculpted material covers ({@link #sculptTile}, its catalogued metres), for
     * the road paint that lays it beside its own ground (a sandy verge) or on its own.
     */
    float sculptCoverage(String name) { return sculptTile(name) / ROAD_REPEAT_METRES; }

    /** One packed layout of a board material, made when its texture is uploaded. */
    private Supplier<Pixmap> packed(String name, GpuMaterialMaps.Layout layout) {
        GodotMaterial material = definition(name);
        // Resolve and check the maps now, so a missing file fails before any upload.
        requireFiles(GpuMaterialMaps.maps(material, layout).stream().map(FileHandle::new).toList());
        return () -> GpuMaterialMaps.pack(material, layout);
    }

    /** A packed layout's dimensions, from its main map's header. */
    private int[] size(String name, GpuMaterialMaps.Layout layout) {
        return GpuMaterialMaps.size(GpuMaterialMaps.primary(definition(name), layout));
    }

    private static final String ICE = "terrain/ice/ice-sheet";

    /**
     * The maps that repeat over several hexes, at their full size ({@link #FULL_SIZE_PLAN}): one sRGB colour array and one
     * raw data array, so ground that reads several of these materials takes two texture units for them. Requires
     * complete sets.
     */
    MaterialArrays fullSizeMaps() {
        if (fullSizeMaps == null) {
            var spreads = new ArrayList<Spread>();
            for (boolean molten : new boolean[] { false, true }) {
                String name = magmaName(molten);
                // The surface's roughness (G) widens by its normal's spread in the mips. One repeat spans
                // magmaRepeatMetres() (magma-flow.glsl, magma-solid.glsl) times the texture's coverage.
                spreads.add(new Spread(layerOf(FULL_SIZE_PLAN, name, GpuMaterialMaps.Layout.NORMAL),
                      layerOf(FULL_SIZE_PLAN, name, GpuMaterialMaps.Layout.RELIEF), 1, (molten ? 24 : 12) * coverage(name)));
            }
            fullSizeMaps = planned(FULL_SIZE_PLAN, spreads);
        }
        return fullSizeMaps;
    }

    /** A plan's pair of arrays, its maps in order. */
    private MaterialArrays planned(List<MapLayer> plan, List<Spread> spreads) {
        var colors = new ArrayList<Layer>();
        var data = new ArrayList<Layer>();
        for (var map : plan) { (map.color() ? colors : data).add(layer(map.material(), map.layout())); }
        return arrays(startedArrays(colors, data, spreads));
    }

    /** {@code coverage} multiplies the ice sheet's repeat (GpuMaterialCatalog). */
    float iceCoverage() { return coverage(ICE); }

    static String magmaName(boolean molten) { return molten ? "terrain/lava/lava-flow" : "terrain/lava/lava-crust"; }

    /** The exported linear emission's scale and area mean, owned with the corresponding material maps. */
    record LavaEmission(float scale, float red, float green, float blue) { }

    LavaEmission lavaEmission() {
        if (lavaEmission != null) { return lavaEmission; }
        GodotMaterial lava = definition(magmaName(true));
        File emission = lava.emission();
        if (emission == null) { throw new IllegalArgumentException("Molten lava needs its emission map: " + lava.file); }
        FileHandle image = new FileHandle(emission);
        requireFiles(List.of(image));
        // The Godot material's emission energy is the exported linear emission's scale.
        float scale = lava.emissionEnergy();
        if (scale < 0) { throw new IllegalArgumentException("Lava emission energy must be nonnegative: " + lava.file); }
        Pixmap pixels = GpuColorTexture.decode(image);
        try {
            long red = 0, green = 0, blue = 0;
            for (int y = 0; y < pixels.getHeight(); y++) {
                for (int x = 0; x < pixels.getWidth(); x++) {
                    int rgba = pixels.getPixel(x, y);
                    red += rgba >>> 24;
                    green += (rgba >>> 16) & 255;
                    blue += (rgba >>> 8) & 255;
                }
            }
            // RGB already contains the authored heat/crack/exposed coverage. Alpha must not weight it again.
            double factor = scale / (255.0 * pixels.getWidth() * pixels.getHeight());
            lavaEmission = new LavaEmission(scale, (float) (red * factor), (float) (green * factor), (float) (blue * factor));
            return lavaEmission;
        } finally { pixels.dispose(); }
    }

    /**
     * A sculpted-terrain material from {@code textures/terrain/surfaces}: albedo with height in alpha, a tangent-space normal
     * with occlusion in alpha, and the metres one repeat spans ({@link #sculptTile}).
     */
    record Sculpt(Texture color, Texture normal, float tile) { }

    /**
     * Aligned colour/height and normal/AO arrays in the supplied stable material order; the data array also carries the
     * finish layers ({@link #finishArrays}) of these materials and of the road materials the data has.
     */
    MaterialArrays sculptArray(List<String> names) {
        if (terrainArrays == null) { terrainArrays = terrainArrays(names, sculptFinishes(names)); }
        return terrainArrays.arrays();
    }

    /** The finishes {@link #sculptArray} holds: the sculpted surfaces', then the road materials' the data has. */
    private List<String> sculptFinishes(List<String> names) {
        var finishes = new ArrayList<String>();
        names.forEach(name -> finishes.add(sculptName(name)));
        for (String road : GpuRoads.MATERIALS) {
            if (hasMaterial("roads/" + road)) { finishes.add("roads/" + road); }
        }
        return finishes;
    }

    /**
     * Starts preparing the terrain's material arrays on the common pool while the view is still being made:
     * {@link #sculptArray}'s for these names, and the {@link #scatter} atlas. Their first use then only uploads them. A
     * failure is reported where they are first used.
     */
    void prepareTerrainArrays(List<String> names) {
        if (terrainArrays == null && preparing == null) {
            try {
                var finishes = sculptFinishes(names);
                preparing = new Preparing(List.copyOf(names), List.copyOf(finishes), terrainWork(names, finishes));
            } catch (RuntimeException failure) {
                // sculptArray reads the same files again and reports what is missing.
            }
        }
        if (!materials.containsKey(SCATTER) && scatterPixels == null) {
            try {
                var definitions = GpuScatter.materials(this);
                scatterPixels = pooled(() -> GpuScatter.pixels(definitions));
            } catch (RuntimeException failure) {
                // scatter reads the same materials again and reports the failure.
            }
        }
    }

    private static String sculptName(String name) { return "terrain/surfaces/" + name; }

    /**
     * The earth-tundra carpets, dry then wet: exported larger than the sculpt grid for their long repeat, they are in
     * {@link #fullSizeMaps}. The sculpt arrays keep a copy at their grid, for the carpets' finish and means, which never
     * widens it.
     */
    static final List<String> TUNDRA_CARPETS = List.of("tundra-ground", "tundra-ground-variant");

    /** A material's map in a pair of arrays, in the colour array or the data array, at its place in its plan. */
    private record MapLayer(String material, GpuMaterialMaps.Layout layout, boolean color) { }

    /**
     * {@link #fullSizeMaps}' layers in order. The earth-tundra carpets, dry then wet: colour/height and normal/AO. The ice
     * sheet: colour, normal and surface, its floes last. Crust and molten lava: colour; normal; surface (height, roughness,
     * occlusion, relief range); heat, where molten heat is normalized linear emission RGB with original heat coverage in A
     * and crust retains R heat and A crack glow.
     */
    private static final List<MapLayer> FULL_SIZE_PLAN = fullSizePlan();
    /** {@link #roadArray}'s layers in order: every road material's colour, normal and surface, then the ice sheet's. */
    private static final List<MapLayer> ROAD_PLAN = roadPlan();
    private static final float[] CRUST_LAYERS = magmaLayers(magmaName(false), GpuMaterialMaps.Layout.CRUST_HEAT),
          LAVA_LAYERS = magmaLayers(magmaName(true), GpuMaterialMaps.Layout.MOLTEN_HEAT);

    private static List<MapLayer> fullSizePlan() {
        var plan = new ArrayList<MapLayer>();
        for (String name : TUNDRA_CARPETS) {
            String material = sculptName(name);
            plan.add(new MapLayer(material, GpuMaterialMaps.colorLayout(material), true));
            plan.add(new MapLayer(material, GpuMaterialMaps.normalLayout(material), false));
        }
        addSet(plan, ICE, GpuMaterialMaps.Layout.NORMAL, GpuMaterialMaps.Layout.ICE);
        addSet(plan, magmaName(false), GpuMaterialMaps.Layout.NORMAL, GpuMaterialMaps.Layout.RELIEF,
              GpuMaterialMaps.Layout.CRUST_HEAT);
        addSet(plan, magmaName(true), GpuMaterialMaps.Layout.NORMAL, GpuMaterialMaps.Layout.RELIEF,
              GpuMaterialMaps.Layout.MOLTEN_HEAT);
        plan.add(new MapLayer(ICE, GpuMaterialMaps.Layout.FLOES, false));
        return List.copyOf(plan);
    }

    private static List<MapLayer> roadPlan() {
        var plan = new ArrayList<MapLayer>();
        for (String name : GpuRoads.MATERIALS) {
            addSet(plan, "roads/" + name, GpuMaterialMaps.Layout.NORMAL, GpuMaterialMaps.Layout.RELIEF);
        }
        addSet(plan, ICE, GpuMaterialMaps.Layout.NORMAL, GpuMaterialMaps.Layout.ICE);
        plan.add(new MapLayer(ICE, GpuMaterialMaps.Layout.FLOES, false));
        return List.copyOf(plan);
    }

    /** A material's colour, then its {@code data} maps. */
    private static void addSet(List<MapLayer> plan, String material, GpuMaterialMaps.Layout... data) {
        plan.add(new MapLayer(material, GpuMaterialMaps.Layout.COLOR, true));
        for (var layout : data) { plan.add(new MapLayer(material, layout, false)); }
    }

    /** Crust's or molten lava's layers in {@link #fullSizeMaps}: colour, then normal, surface and heat (u_magmaLayers). */
    static float[] magmaLayers(boolean molten) { return (molten ? LAVA_LAYERS : CRUST_LAYERS).clone(); }

    private static float[] magmaLayers(String name, GpuMaterialMaps.Layout heat) {
        return new float[] { layerOf(FULL_SIZE_PLAN, name, GpuMaterialMaps.Layout.COLOR),
              layerOf(FULL_SIZE_PLAN, name, GpuMaterialMaps.Layout.NORMAL), layerOf(FULL_SIZE_PLAN, name, GpuMaterialMaps.Layout.RELIEF),
              layerOf(FULL_SIZE_PLAN, name, heat) };
    }

    /** A map's layer in its array of {@code plan}. */
    private static int layerOf(List<MapLayer> plan, String material, GpuMaterialMaps.Layout layout) {
        int colors = 0, data = 0;
        for (var map : plan) {
            if (map.material().equals(material) && map.layout() == layout) { return map.color() ? colors : data; }
            if (map.color()) { colors++; } else { data++; }
        }
        throw new IllegalArgumentException("No " + layout + " of " + material + " in the arrays");
    }

    /** The carpets' layers, dry then wet, in the full-size colour and data arrays (FULL_SIZE_LAYERS, full-size-maps.glsl). */
    static String carpetLayers() {
        String dry = sculptName(TUNDRA_CARPETS.get(0)), wet = sculptName(TUNDRA_CARPETS.get(1));
        return "const vec2 FULL_SIZE_CARPET_COLOR = vec2(" + layerOf(FULL_SIZE_PLAN, dry, GpuMaterialMaps.colorLayout(dry))
              + ".0, " + layerOf(FULL_SIZE_PLAN, wet, GpuMaterialMaps.colorLayout(wet)) + ".0);\n"
              + "const vec2 FULL_SIZE_CARPET_DATA = vec2(" + layerOf(FULL_SIZE_PLAN, dry, GpuMaterialMaps.normalLayout(dry))
              + ".0, " + layerOf(FULL_SIZE_PLAN, wet, GpuMaterialMaps.normalLayout(wet)) + ".0);";
    }

    /** The ice sheet's colour, normal, surface and floe layers in the road or the full-size arrays (terrain-ice.glsl). */
    static String iceLayers(boolean road) {
        var plan = road ? ROAD_PLAN : FULL_SIZE_PLAN;
        return "const float ICE_COLOR = " + layerOf(plan, ICE, GpuMaterialMaps.Layout.COLOR) + ".0, ICE_NORMAL = "
              + layerOf(plan, ICE, GpuMaterialMaps.Layout.NORMAL) + ".0, ICE_SURFACE = "
              + layerOf(plan, ICE, GpuMaterialMaps.Layout.ICE) + ".0, ICE_FLOES = "
              + layerOf(plan, ICE, GpuMaterialMaps.Layout.FLOES) + ".0;";
    }

    /** A layer resampled to the array's grid, whatever its own size. */
    private static Layer fitted(Layer layer) { return new Layer(1, 1, layer.pixels()); }

    /**
     * The finish ({@link #authoredFinish}) of every board material the terrain draws from arrays: sculpted surfaces, then
     * from {@code firstRoad} on the road materials, in the given stable order of material names. Its layers follow the
     * sculpt normal/AO layers in the sculpt data array ({@link #sculptArray}), raw RGBA8, so the terrain's programs read
     * them through a sampler they already have: the surface layer (roughness, metallic and scattering in R, G and B) of
     * each material whose finish varies and, after them, each emission map's sRGB bytes, which the shader decodes. Per
     * material: its surface values or means and surface layer (-1 if uniform), its emission tint and emission layer (-1
     * without a map), and its constant glow. The sculpt maps set the array's grid, which the road materials' maps take;
     * road materials keep their roughness in their own surface map, so theirs is sent as its mean alone. {@code relief}
     * is each sculpted surface's heightmap parallax (GpuMaterialMaps.ModelFinish): its depth along U and V in UV units,
     * zero without a heightmap; the road materials take theirs from {@link #roadRelief}. Each sculpted surface's surface
     * layer widens its roughness in the mips by the spread of its normal/AO layer ({@link Spread}). {@code maps} is the
     * sculpt data array, which the asset owner disposes with the sculpt arrays.
     */
    record FinishArrays(TextureArray maps, float[] surface, float[] tint, float[] glow, float[] relief) { }

    FinishArrays finishArrays(List<String> materials, int firstRoad) {
        if (terrainArrays == null) {
            var names = new ArrayList<String>();
            for (String material : materials.subList(0, firstRoad)) {
                if (!material.startsWith(sculptName(""))) {
                    throw new IllegalArgumentException("The finish arrays begin with sculpted surfaces: " + material);
                }
                names.add(material.substring(sculptName("").length()));
            }
            terrainArrays = terrainArrays(names, materials);
        }
        // Built by sculptArray, they hold the road materials it found; the indices must still be these materials'.
        // A missing material reports its own file first.
        materials.forEach(this::definition);
        List<String> built = terrainArrays.materials();
        if (materials.size() > built.size() || !built.subList(0, materials.size()).equals(materials)) {
            throw new IllegalArgumentException("The terrain arrays hold the finish of " + built + ", not " + materials);
        }
        return terrainArrays.finish();
    }

    /** The sculpt arrays and the finish read from their data array, built together, with the finish's materials. */
    private record TerrainArrays(List<String> materials, MaterialArrays arrays, FinishArrays finish) { }

    /** The sculpt arrays' work before any GL: their data, started, and the finish's values ({@link FinishArrays}). */
    private record TerrainWork(StartedArrays arrays, float[] surface, float[] tint, float[] glow, float[] relief) { }

    /** Work {@link #prepareTerrainArrays} started for these names and materials. */
    private record Preparing(List<String> names, List<String> materials, CompletableFuture<TerrainWork> work) {
        /** Releases the work's prepared layers, as they arrive; none of them is uploaded. */
        void discard() { work.thenAccept(prepared -> prepared.arrays().discard()); }
    }

    /** The work the first sculpt or finish arrays take, or null. */
    private Preparing preparing;
    /** The anisotropy the sculpt arrays filter with, now and when built ({@link #setTerrainAnisotropy}). */
    private float terrainAnisotropy = MATERIAL_ANISOTROPY;

    /** The sculpted terrain's material filtering; GL thread. {@link BoardQuality#terrainAnisotropy} sets it. */
    void setTerrainAnisotropy(float level) {
        if (terrainAnisotropy == level) { return; }
        terrainAnisotropy = level;
        if (terrainArrays != null) { terrainArrays.arrays().anisotropy(level); }
    }

    private TerrainArrays terrainArrays(List<String> names, List<String> materials) {
        var started = preparing;
        preparing = null;
        if (started != null && !(started.names().equals(names) && started.materials().equals(materials))) {
            started.discard();
            started = null;
        }
        var work = joined(started != null ? started.work() : terrainWork(names, materials));
        MaterialArrays arrays = arrays(work.arrays());
        arrays.anisotropy(terrainAnisotropy);
        return new TerrainArrays(List.copyOf(materials), arrays,
              new FinishArrays(arrays.data(), work.surface(), work.tint(), work.glow(), work.relief()));
    }

    /**
     * The sculpt arrays' work as far as it goes without GL. This thread reads the materials and lists the layers; the
     * common pool decodes the finishes, then lays out the arrays and starts preparing their layers.
     */
    private CompletableFuture<TerrainWork> terrainWork(List<String> names, List<String> materials) {
        List<GodotMaterial> materialDefinitions = materials.stream().map(this::definition).toList();
        var reading = new ArrayList<CompletableFuture<AuthoredFinish>>();
        for (int index = 0; index < materialDefinitions.size(); index++) {
            GodotMaterial material = materialDefinitions.get(index);
            boolean roughnessMap = index < names.size();
            reading.add(pooled(() -> authoredFinish(material, roughnessMap)));
        }
        var layers = new ArrayList<Layer>();
        float[] tiles = new float[names.size()];
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index), material = sculptName(name);
            tiles[index] = sculptTile(name);
            Layer color = layer(material, GpuMaterialMaps.colorLayout(material));
            Layer normal = layer(material, GpuMaterialMaps.normalLayout(material));
            boolean carpet = TUNDRA_CARPETS.contains(name);
            layers.add(carpet ? fitted(color) : color);
            layers.add(carpet ? fitted(normal) : normal);
        }
        return CompletableFuture.allOf(reading.toArray(CompletableFuture[]::new))
              .thenApplyAsync(read -> terrainWork(names, materials, materialDefinitions, reading, layers, tiles), pool());
    }

    /** The rest of {@link #terrainWork(List, List)}, once the finishes are read; it touches no state of this owner. */
    private static TerrainWork terrainWork(List<String> names, List<String> materials,
          List<GodotMaterial> materialDefinitions, List<CompletableFuture<AuthoredFinish>> reading, List<Layer> layers,
          float[] tiles) {
        int firstRoad = names.size(), count = materials.size();
        float[] surface = new float[count * 4], tint = new float[count * 4], glow = new float[count * 3];
        float[] relief = new float[firstRoad * 2];
        var surfaces = new ArrayList<Layer>();
        var emissions = new ArrayList<Layer>();
        var surfaceLayers = new int[count];
        var emissionLayers = new int[count];
        for (int index = 0; index < count; index++) {
            var finish = joined(reading.get(index));
            System.arraycopy(finish.surface(), 0, surface, index * 4, 3);
            System.arraycopy(finish.tint(), 0, tint, index * 4, 3);
            System.arraycopy(finish.glow(), 0, glow, index * 3, 3);
            surfaceLayers[index] = finish.map() == null ? -1 : surfaces.size();
            emissionLayers[index] = finish.emission() == null ? -1 : emissions.size();
            // A road material's maps take the sculpt grid; only the sculpted surfaces' maps may widen it, the tundra
            // carpets' excepted.
            boolean sculpted = index < firstRoad;
            boolean widens = sculpted && !TUNDRA_CARPETS.contains(names.get(index));
            if (sculpted) {
                var parallax = GpuMaterialMaps.modelFinish(materialDefinitions.get(index));
                if (parallax.heightmap() != null) {
                    relief[index * 2] = parallax.depthU();
                    relief[index * 2 + 1] = parallax.depthV();
                }
            }
            if (finish.map() != null) {
                surfaces.add(new Layer(widens ? finish.width() : 1, widens ? finish.height() : 1,
                      () -> rgbaPixmap(finish.map(), finish.width(), finish.height())));
            }
            if (finish.emission() != null) {
                FileHandle map = new FileHandle(finish.emission());
                int[] size = widens ? GpuMaterialMaps.size(finish.emission()) : new int[] { 1, 1 };
                emissions.add(new Layer(size[0], size[1], () -> GpuColorTexture.decode(map)));
            }
        }
        // The finish layers follow one normal/AO layer per sculpted surface in the data array. A surface's roughness (R)
        // widens by its normal's spread in the mips, as mud's and scorch's own (B) does.
        var spreads = new ArrayList<Spread>();
        for (int index = 0; index < count; index++) {
            surface[index * 4 + 3] = surfaceLayers[index] < 0 ? -1 : firstRoad + surfaceLayers[index];
            tint[index * 4 + 3] = emissionLayers[index] < 0 ? -1 : firstRoad + surfaces.size() + emissionLayers[index];
            if (index >= firstRoad) { continue; }
            float tile = tiles[index];
            if (surfaceLayers[index] >= 0) { spreads.add(new Spread(index, firstRoad + surfaceLayers[index], 0, tile)); }
            if (GpuMaterialMaps.PACKED_ROUGHNESS.contains(materials.get(index))) {
                spreads.add(new Spread(index, index, 2, tile));
            }
        }
        var finishes = new ArrayList<>(surfaces);
        finishes.addAll(emissions);
        return new TerrainWork(startedArrays(layers, 2, finishes, spreads), surface, tint, glow, relief);
    }

    private static Pixmap rgbaPixmap(byte[] rgba, int width, int height) {
        var pixels = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        pixels.getPixels().put(rgba).position(0);
        return pixels;
    }

    /**
     * A material's finish as Godot's StandardMaterial3D reads it. Roughness, metallic and subsurface scattering: each its
     * map's channel times its factor ({@code roughness}, {@code metallic}, {@code subsurf_scatter_strength}), or the
     * factor alone without a map (Godot's maps default to white); scattering only where it is enabled. Where all three
     * are uniform they are {@code surface}; otherwise {@code map} holds them in R, G and B at the largest of their grids,
     * and {@code surface} their means, which distant terrain uses instead of reading the map. Emission: its map, the
     * map's tint and a constant glow ({@link GpuMaterialMaps#emission}).
     */
    record AuthoredFinish(float[] surface, byte[] map, int width, int height, float[] tint, File emission, float[] glow) { }

    /** {@link #finishArrays}' rule for one material, without a graphics context. */
    static AuthoredFinish authoredFinish(GodotMaterial material) { return authoredFinish(material, true); }

    /** Without its roughness map, a material whose roughness another map carries sends only the map's mean. */
    static AuthoredFinish authoredFinish(GodotMaterial material, boolean roughnessMap) {
        boolean scatters = material.subsurfaceEnabled();
        var channels = new FinishChannel[] {
              withoutMap(finishChannel(material, "roughness", material.roughness(), material.roughnessMap(),
                    material.roughnessChannel()), !roughnessMap),
              finishChannel(material, "metallic", material.metallic(), material.metallicMap(), material.metallicChannel()),
              finishChannel(material, "subsurf_scatter_strength", scatters ? material.subsurfaceStrength() : 0,
                    scatters ? material.subsurface() : null, GodotMaterial.RED) };
        float[] surface = new float[3];
        int width = 1, height = 1;
        for (var channel : channels) {
            if (channel.map() == null) { continue; }
            width = Math.max(width, channel.map().width());
            height = Math.max(height, channel.map().height());
        }
        byte[] map = null;
        for (int index = 0; index < 3; index++) {
            var channel = channels[index];
            surface[index] = channel.value();
            if (channel.map() == null) { continue; }
            if (map == null) {
                map = new byte[width * height * 4];
                for (int other = 0; other < 3; other++) { map[other] = (byte) Math.round(channels[other].value() * 255); }
                map[3] = (byte) 255;
                // Every texel repeats the first: copy the filled part onto the rest, doubling it.
                for (int filled = 4; filled < map.length; filled *= 2) {
                    System.arraycopy(map, 0, map, filled, Math.min(filled, map.length - filled));
                }
            }
            byte[] values = onGrid(channel.map(), width, height);
            for (int texel = 0; texel < values.length; texel++) { map[texel * 4 + index] = values[texel]; }
        }
        var emission = GpuMaterialMaps.emission(material);
        if (emission.map() != null) { requireFiles(List.of(new FileHandle(emission.map()))); }
        return new AuthoredFinish(surface, map, width, height, emission.tint(), emission.map(), emission.glow());
    }

    /** One finish channel: its value (a map's mean), and the map times its factor where it varies. */
    private record FinishChannel(float value, GpuMaterialMaps.Channel map) { }

    private static FinishChannel withoutMap(FinishChannel channel, boolean drop) {
        return drop ? new FinishChannel(channel.value(), null) : channel;
    }

    private static FinishChannel finishChannel(GodotMaterial material, String property, float factor, File map,
          int channel) {
        if (factor < 0 || factor > 1) { throw new IllegalArgumentException("Invalid " + property + ": " + material.file); }
        if (map == null || factor == 0) { return new FinishChannel(factor, null); }
        requireFiles(List.of(new FileHandle(map)));
        var read = GpuMaterialMaps.channel(map, channel);
        byte[] values = read.values();
        boolean uniform = true;
        for (byte value : values) { uniform &= value == values[0]; }
        if (uniform) { return new FinishChannel((values[0] & 255) / 255f * factor, null); }
        long sum = 0;
        for (int texel = 0; texel < values.length; texel++) {
            if (factor != 1) { values[texel] = (byte) Math.round((values[texel] & 255) * factor); }
            sum += values[texel] & 255;
        }
        return new FinishChannel(sum / 255f / values.length, read);
    }

    /** A channel on a grid, as {@link GpuColorTexture#arrayPixels} resamples a colour map; a uniform one fills it. */
    static byte[] onGrid(GpuMaterialMaps.Channel channel, int width, int height) {
        width = Math.max(width, 1);
        height = Math.max(height, 1);
        if (channel.width() == width && channel.height() == height) { return channel.values(); }
        byte[] values = new byte[width * height];
        if (channel.values().length == 1) {
            Arrays.fill(values, channel.values()[0]);
            return values;
        }
        var source = new Pixmap(channel.width(), channel.height(), Pixmap.Format.RGBA8888);
        try {
            var pixels = source.getPixels();
            for (byte value : channel.values()) { pixels.put(value).put(value).put(value).put((byte) 255); }
            pixels.position(0);
            var resized = GpuColorTexture.arrayPixels(source, width, height, false);
            for (int texel = 0; texel < values.length; texel++) { values[texel] = resized.get(texel * 4); }
            return values;
        } finally { source.dispose(); }
    }

    /**
     * Every road material's maps ({@link #ROAD_PLAN}), so that one draw holds all of a chunk's road coats, and the ice
     * sheet's, which ice on a road reads here and takes no other texture unit (terrain-ice.glsl). All maps are required.
     */
    MaterialArrays roadArray() {
        if (roadArray == null) { roadArray = planned(ROAD_PLAN, List.of()); }
        return roadArray;
    }

    /** One owned pair: sRGB colour/height and raw numerical maps, each compacted in material order. */
    record MaterialArrays(TextureArray color, TextureArray data) implements Disposable {
        @Override public void dispose() { color.dispose(); data.dispose(); }

        void anisotropy(float level) {
            setAnisotropy(color, level);
            setAnisotropy(data, level);
        }
    }

    /** The anisotropy of repeating material arrays, as far as the driver offers. */
    static final float MATERIAL_ANISOTROPY = 8;

    private static void setAnisotropy(TextureArray array, float level) {
        float anisotropy = Math.min(level, GLTexture.getMaxAnisotropicFilterLevel());
        if (anisotropy > 1) {
            // GLTexture.setAnisotropicFilter targets 2D, so set the array's own target explicitly.
            array.bind();
            Gdx.gl.glTexParameterf(GL30.GL_TEXTURE_2D_ARRAY, GL20.GL_TEXTURE_MAX_ANISOTROPY_EXT, anisotropy);
        }
    }

    /** One array layer: a board material's packed layout and its dimensions. */
    record Layer(int width, int height, Supplier<Pixmap> pixels) { }

    private Layer layer(String name, GpuMaterialMaps.Layout layout) {
        int[] size = size(name, layout);
        return new Layer(size[0], size[1], packed(name, layout));
    }

    /** A colour and a data array's layers, being prepared on the common pool ({@link MaterialArrayData#start}). */
    private record StartedArrays(MaterialArrayData color, MaterialArrayData data) {
        /** Releases the prepared layers that will not be uploaded. */
        void discard() { color.discard(); data.discard(); }
    }

    /**
     * Sets of aligned maps, without GL: their first map is colour and all remaining maps carry numerical data; {@code extra}
     * data layers follow the sets' own, and the data array's normals widen the roughness named by {@code spreads} in its
     * mips. Both arrays start preparing their layers.
     */
    private static StartedArrays startedArrays(List<Layer> layers, int maps, List<Layer> extra, List<Spread> spreads) {
        if (layers.isEmpty() || maps < 2 || layers.size() % maps != 0) {
            throw new IllegalArgumentException("Incomplete repeating material arrays");
        }
        var colors = new ArrayList<Layer>();
        var data = new ArrayList<Layer>();
        for (int index = 0; index < layers.size(); index++) { (index % maps == 0 ? colors : data).add(layers.get(index)); }
        data.addAll(extra);
        return startedArrays(colors, data, spreads);
    }

    /**
     * Both arrays at the largest layer's grid, so a material exported larger keeps its detail; smaller layers are
     * resampled to it. Their layers started.
     */
    private static StartedArrays startedArrays(List<Layer> colors, List<Layer> data, List<Spread> spreads) {
        int width = 0, height = 0;
        for (List<Layer> layers : List.of(colors, data)) {
            for (Layer layer : layers) {
                width = Math.max(width, layer.width());
                height = Math.max(height, layer.height());
            }
        }
        var started = new StartedArrays(new MaterialArrayData(colors.stream().map(Layer::pixels).toList(), width, height, true),
              new MaterialArrayData(data.stream().map(Layer::pixels).toList(), width, height, false, spreads));
        started.color().start();
        started.data().start();
        return started;
    }

    /** The GL arrays of started layers, uploaded in order; after a failure the layers still prepared are released. */
    private static MaterialArrays arrays(StartedArrays started) {
        TextureArray color = null, numeric = null;
        try {
            color = repeatingArray(started.color());
            numeric = repeatingArray(started.data());
            return new MaterialArrays(color, numeric);
        } catch (RuntimeException | Error failure) {
            started.discard();
            if (numeric != null) { numeric.dispose(); }
            if (color != null) { color.dispose(); }
            throw failure;
        }
    }

    private static TextureArray repeatingArray(MaterialArrayData data) {
        var array = new TextureArray(data);
        try {
            // Own the allocated handle before decoding/uploading: any source failure releases this array.
            array.bind();
            data.upload();
            array.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            array.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
            setAnisotropy(array, MATERIAL_ANISOTROPY);
            return array;
        } catch (RuntimeException | Error failure) { array.dispose(); throw failure; }
    }

    /**
     * Work on the common pool, so at most its parallelism runs at once; without at least two of its threads (where
     * supplyAsync's default would start a thread per task) the work runs at once, on the calling thread. Whoever asks
     * for the result ({@link #joined}) before a pool thread took the work runs it itself: at a load the pool is busy
     * with long work (material packs, model files), and the render thread would wait behind all of it for a few
     * milliseconds of its own.
     */
    static <T> CompletableFuture<T> pooled(Supplier<T> work) {
        Pooled<T> task = new Pooled<>(work);
        pool().execute(task);
        return task;
    }

    /** {@link #pooled} work: run once, by the pool or by the thread that joins it first. */
    private static final class Pooled<T> extends CompletableFuture<T> implements Runnable {
        private final Supplier<T> work;
        private final java.util.concurrent.atomic.AtomicBoolean taken = new java.util.concurrent.atomic.AtomicBoolean();

        Pooled(Supplier<T> work) { this.work = work; }

        @Override
        public void run() {
            if (isDone() || !taken.compareAndSet(false, true)) { return; }
            try {
                complete(work.get());
            } catch (Throwable failure) {
                completeExceptionally(failure);
            }
        }
    }

    /** {@link #pooled}'s executor. */
    private static Executor pool() {
        return ForkJoinPool.getCommonPoolParallelism() > 1 ? ForkJoinPool.commonPool() : Runnable::run;
    }

    /** The result of {@link #pooled} work, or the work's own failure. */
    static <T> T joined(CompletableFuture<T> work) {
        if (work instanceof Pooled<T> task) { task.run(); }
        try {
            return work.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException cause) { throw cause; }
            if (failure.getCause() instanceof Error cause) { throw cause; }
            throw failure;
        }
    }

    /**
     * Roughness that a normal layer's spread widens in the mips of a data array ({@link #mipmaps}): channel
     * {@code channel} of layer {@code target}, both data layer indices, for a normal whose repeat spans {@code tile}
     * metres. A normal layer that is its own target holds X and Y only, with its roughness in B
     * ({@link GpuMaterialMaps.Layout#NORMAL_ROUGHNESS}).
     */
    record Spread(int normal, int target, int channel, float tile) { }

    /**
     * The mip level the close view ({@link GpuMaterialCache#CLOSE_PIXEL}, the look every zoom averages) reads of a
     * {@code width}-texel map repeating every {@code tile} metres: the finer of the two its trilinear filter blends, so
     * every coarser level carries all the spread the close view averages.
     */
    static int closeLevel(int width, float tile) {
        return Math.max(0, (int) Math.floor(Math.log(GpuMaterialCache.CLOSE_PIXEL * width / tile) / Math.log(2) + 1e-4));
    }

    /**
     * The GGX alpha^2 (roughness^4, light-model.glsl) that unit normals averaging to length l add to their own mean: a
     * von Mises-Fisher lobe of concentration kappa = l (3 - l^2) / (1 - l^2) adds 1 / (2 kappa), and normals that cancel
     * read fully rough, as the ground material cache widens its alpha^2 (filteredAlpha2, terrain-sculpt.frag).
     * Agreement within the float sums' rounding (1e-6, finer than two neighbouring 8-bit normals) adds nothing: the
     * fourth root would turn it into visible roughness on a smooth surface.
     */
    static float spreadAlpha2(float l) {
        if (l >= .999999f) { return 0; }
        float l2 = l * l;
        return (1 - l2) / Math.max(2 * l * (3 - l2), 1e-6f);
    }

    /** A byte as a value in [0, 1], as GGX alpha^2 (roughness^4) and as a signed tangent normal component. */
    private static final float[] UNIT = new float[256], ALPHA2 = new float[256], SIGNED = new float[256];

    static {
        for (int code = 0; code < 256; code++) {
            UNIT[code] = code / 255f;
            ALPHA2[code] = UNIT[code] * UNIT[code] * UNIT[code] * UNIT[code];
            SIGNED[code] = code / 127.5f - 1;
        }
    }

    /**
     * The mip levels below 0 of one unit of a data array's layers, level 0 being the layers themselves (width by height
     * RGBA8 texels). Every channel holds the exact means of level 0 over its texels, reduced level by level. Where
     * {@code roughness} names a layer's roughness channel (-1 for none), layer 0 is the unit's normal: tangent X, Y and
     * Z in RGB, or X and Y alone where it names layer 0's own B (Z rebuilt). Up to the close view's level {@code close}
     * ({@link #closeLevel}) the normal is its unit normals' mean direction and the roughness its plain mean, as the
     * close view has always shaded them. Above it, the levels average the close level's unit normals, unnormalised (the length
     * is their agreement; X and Y alone stay renormalised, as a shader rebuilds Z), and its roughness as alpha^2, which
     * those normals' spread widens ({@link #spreadAlpha2}): the close view averaged.
     */
    static List<List<ByteBuffer>> mipmaps(List<ByteBuffer> layers, int width, int height, int[] roughness, int close) {
        int count = layers.size();
        boolean normal = Arrays.stream(roughness).anyMatch(channel -> channel >= 0), packed = roughness[0] >= 0;
        int normalChannels = normal ? (packed ? 2 : 3) : 0;
        // Level 1 straight from level 0's bytes, each later level from the one before; the channels the normal writes
        // keep no means of their own.
        float[][] means = new float[count * 4][], normals = new float[0][];
        boolean even = width % 2 == 0 && height % 2 == 0;
        for (int layer = 0; layer < count; layer++) {
            byte[] texels = new byte[width * height * 4];
            layers.get(layer).get(0, texels);
            if (layer == 0 && normal) {
                normals = even ? halvedNormals(texels, width, height, packed) : unitNormals(texels, width * height, packed);
                for (int axis = 0; !even && axis < 3; axis++) { normals[axis] = halved(normals[axis], width, height); }
            }
            for (int channel = layer == 0 ? normalChannels : 0; channel < 4; channel++) {
                float[] decode = channel == roughness[layer] && close == 0 ? ALPHA2 : UNIT;
                means[layer * 4 + channel] = even ? halved(texels, channel, width, height, decode)
                      : halved(decoded(texels, channel, decode), width, height);
            }
        }
        var mipmaps = new ArrayList<List<ByteBuffer>>();
        for (int layer = 0; layer < count; layer++) { mipmaps.add(new ArrayList<>()); }
        for (int level = 1, w = Math.max(1, width >> 1), h = Math.max(1, height >> 1); ; level++) {
            boolean averaged = level > close;
            float[] lengths = normal ? lengths(normals) : null;
            for (int layer = 0; layer < count; layer++) {
                byte[] bytes = new byte[w * h * 4];
                for (int channel = 0; channel < 4; channel++) {
                    if (layer == 0 && channel < normalChannels) {
                        encodeNormal(normals[channel], packed || !averaged ? lengths : null, bytes, channel);
                    } else if (channel == roughness[layer] && averaged) {
                        encodeRoughness(means[layer * 4 + channel], lengths, bytes, channel);
                    } else {
                        encode(means[layer * 4 + channel], bytes, channel);
                    }
                }
                mipmaps.get(layer).add(com.badlogic.gdx.utils.BufferUtils.newByteBuffer(bytes.length).put(bytes).flip());
            }
            if (w == 1 && h == 1) { return mipmaps; }
            if (level == close) {
                // The close view's level: its unit normals and its roughness as alpha^2 are what the coarser levels average.
                for (int texel = 0; normal && texel < lengths.length; texel++) {
                    float scale = lengths[texel] > 1e-6f ? 1 / lengths[texel] : 0;
                    for (float[] axis : normals) { axis[texel] *= scale; }
                }
                for (int layer = 0; layer < count; layer++) {
                    if (roughness[layer] < 0) { continue; }
                    float[] values = means[layer * 4 + roughness[layer]];
                    for (int texel = 0; texel < values.length; texel++) {
                        values[texel] = values[texel] * values[texel] * values[texel] * values[texel];
                    }
                }
            }
            for (int index = 0; index < means.length; index++) {
                if (means[index] != null) { means[index] = halved(means[index], w, h); }
            }
            for (int axis = 0; axis < normals.length; axis++) { normals[axis] = halved(normals[axis], w, h); }
            w = Math.max(1, w >> 1);
            h = Math.max(1, h >> 1);
        }
    }

    /** One channel's level 1 from level 0's RGBA bytes: the mean of each 2 by 2 texels' decoded values. */
    private static float[] halved(byte[] texels, int channel, int width, int height, float[] decode) {
        int halfWidth = Math.max(1, width >> 1), halfHeight = Math.max(1, height >> 1);
        float[] half = new float[halfWidth * halfHeight];
        for (int y = 0; y < halfHeight; y++) {
            int row0 = Math.min(2 * y, height - 1) * width, row1 = Math.min(2 * y + 1, height - 1) * width;
            for (int x = 0; x < halfWidth; x++) {
                int x0 = Math.min(2 * x, width - 1), x1 = Math.min(2 * x + 1, width - 1);
                half[y * halfWidth + x] = .25f * (decode[texels[(row0 + x0) * 4 + channel] & 255]
                      + decode[texels[(row0 + x1) * 4 + channel] & 255] + decode[texels[(row1 + x0) * 4 + channel] & 255]
                      + decode[texels[(row1 + x1) * 4 + channel] & 255]);
            }
        }
        return half;
    }

    /** The mean unit normal of each 2 by 2 texels of a normal layer's RGB (or RG, Z rebuilt) bytes: level 1. */
    private static float[][] halvedNormals(byte[] texels, int width, int height, boolean packed) {
        int halfWidth = Math.max(1, width >> 1), halfHeight = Math.max(1, height >> 1);
        float[][] half = new float[3][halfWidth * halfHeight];
        for (int y = 0; y < halfHeight; y++) {
            int row0 = Math.min(2 * y, height - 1) * width, row1 = Math.min(2 * y + 1, height - 1) * width;
            for (int x = 0; x < halfWidth; x++) {
                int x0 = Math.min(2 * x, width - 1), x1 = Math.min(2 * x + 1, width - 1);
                int at = y * halfWidth + x;
                addUnitNormal(texels, row0 + x0, packed, half, at);
                addUnitNormal(texels, row0 + x1, packed, half, at);
                addUnitNormal(texels, row1 + x0, packed, half, at);
                addUnitNormal(texels, row1 + x1, packed, half, at);
            }
        }
        return half;
    }

    /** A quarter of one texel's unit normal, added to the sums at {@code at}. */
    private static void addUnitNormal(byte[] texels, int texel, boolean packed, float[][] sums, int at) {
        float x = SIGNED[texels[texel * 4] & 255], y = SIGNED[texels[texel * 4 + 1] & 255];
        float z = packed ? (float) Math.sqrt(Math.max(0, 1 - x * x - y * y)) : SIGNED[texels[texel * 4 + 2] & 255];
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        float scale = length > 0 ? .25f / length : 0;
        sums[0][at] += x * scale;
        sums[1][at] += y * scale;
        sums[2][at] += z * scale;
    }

    /** One channel of RGBA bytes, decoded. */
    private static float[] decoded(byte[] texels, int channel, float[] decode) {
        float[] values = new float[texels.length / 4];
        for (int texel = 0; texel < values.length; texel++) { values[texel] = decode[texels[texel * 4 + channel] & 255]; }
        return values;
    }

    /** Each texel's unit normal from a normal layer's RGB (or RG, Z rebuilt) bytes. */
    private static float[][] unitNormals(byte[] texels, int count, boolean packed) {
        float[][] normals = new float[3][count];
        for (int texel = 0; texel < count; texel++) {
            addUnitNormal(texels, texel, packed, normals, texel);
            for (float[] axis : normals) { axis[texel] *= 4; }
        }
        return normals;
    }

    /**
     * One array's next mip level: each texel the mean of the 2 by 2 texels it covers. Along an odd side each covers its
     * share of the texels (2.5 of 5), so every level keeps the mean of level 0.
     */
    private static float[] halved(float[] source, int width, int height) {
        int halfWidth = Math.max(1, width >> 1), halfHeight = Math.max(1, height >> 1);
        if (width % 2 != 0 || height % 2 != 0) {
            return resampled(resampled(source, width, height, halfWidth, true), halfWidth, height, halfHeight, false);
        }
        float[] half = new float[halfWidth * halfHeight];
        for (int y = 0; y < halfHeight; y++) {
            int row0 = Math.min(2 * y, height - 1) * width, row1 = Math.min(2 * y + 1, height - 1) * width;
            for (int x = 0; x < halfWidth; x++) {
                int x0 = Math.min(2 * x, width - 1), x1 = Math.min(2 * x + 1, width - 1);
                half[y * halfWidth + x] = .25f * (source[row0 + x0] + source[row0 + x1] + source[row1 + x0] + source[row1 + x1]);
            }
        }
        return half;
    }

    /** A box filter along one side, to {@code size} texels: each the area-weighted mean of the texels it covers. */
    private static float[] resampled(float[] source, int width, int height, int size, boolean across) {
        int length = across ? width : height, lines = across ? height : width;
        float[] target = new float[across ? size * height : width * size];
        double scale = (double) length / size;
        for (int line = 0; line < lines; line++) {
            for (int out = 0; out < size; out++) {
                double start = out * scale, end = start + scale, sum = 0;
                for (int in = (int) start; in < Math.min(length, (int) Math.ceil(end)); in++) {
                    float value = across ? source[line * width + in] : source[in * width + line];
                    sum += value * (Math.min(end, in + 1) - Math.max(start, in));
                }
                target[across ? line * size + out : out * width + line] = (float) (sum / scale);
            }
        }
        return target;
    }

    private static float[] lengths(float[][] normals) {
        float[] lengths = new float[normals[0].length];
        for (int texel = 0; texel < lengths.length; texel++) {
            lengths[texel] = (float) Math.sqrt(normals[0][texel] * normals[0][texel] + normals[1][texel] * normals[1][texel]
                  + normals[2][texel] * normals[2][texel]);
        }
        return lengths;
    }

    private static void encode(float[] means, byte[] level, int channel) {
        for (int texel = 0; texel < means.length; texel++) { level[texel * 4 + channel] = toByte(means[texel] * 255); }
    }

    /** Signed components, renormalised by {@code lengths} where given. */
    private static void encodeNormal(float[] components, float[] lengths, byte[] level, int channel) {
        for (int texel = 0; texel < components.length; texel++) {
            float component = components[texel];
            if (lengths != null) { component = lengths[texel] > 1e-6f ? component / lengths[texel] : 0; }
            level[texel * 4 + channel] = toByte(component * 127.5f + 127.5f);
        }
    }

    private static void encodeRoughness(float[] alpha2, float[] lengths, byte[] level, int channel) {
        for (int texel = 0; texel < alpha2.length; texel++) {
            float widened = Math.min(1, alpha2[texel] + spreadAlpha2(lengths[texel]));
            level[texel * 4 + channel] = toByte((float) Math.sqrt(Math.sqrt(widened)) * 255);
        }
    }

    private static byte toByte(float value) { return (byte) Math.round(Math.clamp(value, 0f, 255f)); }

    /**
     * A single storage domain; source bytes are unchanged unless the existing grid requires resizing. A data array with
     * {@link Spread}s builds its own mips ({@link #mipmaps}); any other array has the driver generate them.
     */
    private static final class MaterialArrayData implements TextureArrayData {
        /**
         * Units decoded, packed and resized ahead of their upload: enough to keep the common pool busy while the GL
         * thread uploads in unit order, few enough to bound the pixels held at once.
         */
        private static final int AHEAD = Math.max(2, 2 * ForkJoinPool.getCommonPoolParallelism());
        private final List<Supplier<Pixmap>> files;
        private final int width, height;
        private final boolean color, mipmaps;
        /**
         * The layers prepared together (a normal with the layers whose roughness it widens), their roughness channels and
         * the normal's close level ({@link #closeLevel}).
         */
        private final List<int[]> units = new ArrayList<>(), roughness = new ArrayList<>();
        private final List<Integer> close = new ArrayList<>();
        /** The units started ({@link #start}) and not yet uploaded, in unit order, and the next unit to start. */
        private final ArrayDeque<CompletableFuture<List<Prepared>>> ahead = new ArrayDeque<>();
        private int next;
        private boolean prepared;

        MaterialArrayData(List<Supplier<Pixmap>> files, int width, int height, boolean color) {
            this(files, width, height, color, List.of());
        }

        MaterialArrayData(List<Supplier<Pixmap>> files, int width, int height, boolean color, List<Spread> spreads) {
            this.files = List.copyOf(files);
            this.width = width;
            this.height = height;
            this.color = color;
            mipmaps = !spreads.isEmpty();
            boolean[] grouped = new boolean[files.size()];
            spreads.forEach(spread -> grouped[spread.target()] = spread.target() != spread.normal());
            for (int layer = 0; layer < files.size(); layer++) {
                if (grouped[layer]) { continue; }
                var unit = new ArrayList<>(List.of(layer));
                var channels = new ArrayList<>(List.of(-1));
                int level = 0;
                for (Spread spread : spreads) {
                    if (spread.normal() != layer) { continue; }
                    level = closeLevel(width, spread.tile());
                    if (spread.target() == layer) {
                        channels.set(0, spread.channel());
                    } else {
                        unit.add(spread.target());
                        channels.add(spread.channel());
                    }
                }
                units.add(unit.stream().mapToInt(Integer::intValue).toArray());
                roughness.add(channels.stream().mapToInt(Integer::intValue).toArray());
                close.add(level);
            }
        }

        @Override public boolean isPrepared() { return prepared; }
        @Override public void prepare() { prepared = true; }
        @Override public int getWidth() { return width; }
        @Override public int getHeight() { return height; }
        @Override public int getDepth() { return files.size(); }
        @Override public boolean isManaged() { return false; }
        @Override public int getInternalFormat() { return GL20.GL_RGBA; }
        @Override public int getGLType() { return GL20.GL_UNSIGNED_BYTE; }

        @Override
        public void consumeTextureArrayData() {
            // libGDX's allocator shares internal/input enums; specify the sized storage here.
            int levels = mipmaps ? 32 - Integer.numberOfLeadingZeros(Math.max(width, height)) : 1;
            for (int level = 0; level < levels; level++) {
                Gdx.gl30.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, level, color ? GL30.GL_SRGB8_ALPHA8 : GL30.GL_RGBA8,
                      Math.max(1, width >> level), Math.max(1, height >> level), getDepth(), 0, GL20.GL_RGBA,
                      GL20.GL_UNSIGNED_BYTE, (java.nio.Buffer) null);
            }
            prepared = false;
        }

        /** One layer's pixels and the uploads of its mip levels, level 0 first, which may borrow them. */
        private record Prepared(Pixmap pixels, List<ByteBuffer> levels) { }

        private List<Prepared> prepare(int[] unit, int[] channels, int level) {
            var pixels = new ArrayList<Pixmap>();
            try {
                var layers = new ArrayList<ByteBuffer>();
                for (int layer : unit) {
                    pixels.add(files.get(layer).get());
                    layers.add(GpuColorTexture.arrayPixels(pixels.get(pixels.size() - 1), width, height, color));
                }
                var mips = mipmaps ? GpuAssets.mipmaps(layers, width, height, channels, level) : null;
                var prepared = new ArrayList<Prepared>();
                for (int index = 0; index < unit.length; index++) {
                    var levels = new ArrayList<>(List.of(layers.get(index)));
                    if (mips != null) { levels.addAll(mips.get(index)); }
                    prepared.add(new Prepared(pixels.get(index), levels));
                }
                return prepared;
            } catch (RuntimeException | Error failure) { pixels.forEach(Pixmap::dispose); throw failure; }
        }

        /**
         * The caller has an owned TextureArray handle and releases it if source decoding fails. Units decode, pack,
         * resize and build their mips on the common pool; only their uploads, in unit order, stay on this GL thread.
         */
        void upload() {
            var oldAlignment = com.badlogic.gdx.utils.BufferUtils.newIntBuffer(1);
            Gdx.gl.glGetIntegerv(GL20.GL_UNPACK_ALIGNMENT, oldAlignment);
            Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, 1);
            try {
                for (int[] unit : units) {
                    start();
                    var prepared = joined(ahead.remove());
                    try {
                        for (int index = 0; index < unit.length; index++) {
                            var levels = prepared.get(index).levels();
                            for (int level = 0; level < levels.size(); level++) {
                                Gdx.gl30.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, level, 0, 0, unit[index],
                                      Math.max(1, width >> level), Math.max(1, height >> level), 1, GL20.GL_RGBA,
                                      GL20.GL_UNSIGNED_BYTE, levels.get(level));
                            }
                        }
                    } finally {
                        Reference.reachabilityFence(prepared);
                        prepared.forEach(layer -> layer.pixels().dispose());
                    }
                }
            } finally {
                Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, oldAlignment.get(0));
                // After a failure, the units still on their way are released as they arrive.
                discard();
            }
            if (!mipmaps) { Gdx.gl.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY); }
        }

        /** Starts preparing the next units on the common pool, up to {@link #AHEAD} ahead of the upload. */
        void start() {
            for (; next < units.size() && ahead.size() < AHEAD; next++) {
                int[] layers = units.get(next), channels = roughness.get(next);
                int level = close.get(next);
                ahead.add(pooled(() -> prepare(layers, channels, level)));
            }
        }

        /** Releases the started units, as they arrive; none of them is uploaded. */
        void discard() {
            ahead.forEach(pending -> pending.thenAccept(prepared -> prepared.forEach(layer -> layer.pixels().dispose())));
            ahead.clear();
        }
    }

    record Animation<T>(List<T> frames, float[] ends, float duration) {
        T at(float time) {
            return frames.get(index(time));
        }

        int index(float time) {
            float position = time % duration;
            for (int index = 0; index < ends.length; index++) {
                if (position < ends[index]) {
                    return index;
                }
            }
            return frames.size() - 1;
        }

        float blend(float time, int index) {
            float start = index == 0 ? 0 : ends[index - 1];
            return Math.clamp((time % duration - start) / (ends[index] - start), 0, 1);
        }
    }

    Model model(String name) {
        return lodModel(name, 0);
    }

    /**
     * The model in {@code colours} (a layout row's, a truss deck's or an editor preview's): the model itself, or one copy
     * per set of colours with its colour slots replaced ({@link RigidGlb#recolour}). Objects of one colour share their
     * copy, so they still batch together. Placed objects draw the shared mesh instead ({@link #slotted}).
     */
    Model model(String name, megamek.common.board.BoardDecoration.Colours colours) { return model(name, colours, ""); }

    /** {@link #model(String, BoardDecoration.Colours)} with {@code label} written on its label materials ({@link RigidGlb#label}). */
    Model model(String name, megamek.common.board.BoardDecoration.Colours colours, String label) {
        return model(name, colours, label, 0);
    }

    /** {@link #model(String, BoardDecoration.Colours, String)} at a mesh level: each level has its own copy. */
    private Model model(String name, megamek.common.board.BoardDecoration.Colours colours, String label, int level) {
        if (colours.slots().isEmpty() && label.isEmpty()) { return lodModel(name, level); }
        if (unloadable.containsKey(name)) { return missing(name, unloadable.get(name)); }
        try {
            int own = ownLevel(name, level);
            // A copy's key cannot meet a level's name ("name-lodN") or a slotted mesh's ("...#slots"): it holds "[".
            return fileModel((own == 0 ? name : MeshLod.name(name, own)) + colours.slots() + "#" + label, name, () -> {
                // The file is decoded once; each set of colours and label changes a copy of its vertices for its own mesh.
                ModelData data = decoded(name).get(own);
                var mesh = data.meshes.first();
                float[] vertices = mesh.vertices;
                mesh.vertices = vertices.clone();
                try {
                    RigidGlb.recolour(data, colours);
                    RigidGlb.label(data, label);
                    return createModel(data);
                } finally { mesh.vertices = vertices; }
            });
        } catch (RuntimeException failure) { return missing(name, reason(failure)); }
    }

    /**
     * {@link #levels(String, BoardDecoration.Colours, String)} in the model's own colours, without a label: one list
     * per model file, as every tree and rock of a chunk build asks for it.
     */
    List<Model> levels(String name) {
        List<Model> known = plainLevels.get(name);
        if (known != null) { return known; }
        List<Model> levels = List.copyOf(propLevels(level -> lodModel(name, level)));
        // Only a model file's own models live as long as these assets: the marked box and generated models do not.
        if (modelFiles.containsKey(levels.getFirst())) { plainLevels.put(name, levels); }
        return levels;
    }

    /**
     * A model file's mesh per level of the props' rule ({@link TreeLod#PROPS}), near mesh first, in {@code colours} and
     * with {@code label} as {@link #model(String, BoardDecoration.Colours, String)} makes them: what a prop draws its
     * levels from. A file with one level gives a list of one.
     */
    List<Model> levels(String name, megamek.common.board.BoardDecoration.Colours colours, String label) {
        return colours.slots().isEmpty() && label.isEmpty() ? levels(name)
              : propLevels(level -> model(name, colours, label, level));
    }

    /** The levels of the props' rule, without the trailing repeats of a file that lacks its last ones. */
    private static List<Model> propLevels(java.util.function.IntFunction<Model> mesh) {
        List<Model> result = new ArrayList<>(TreeLod.PROPS.length + 1);
        for (int level = 0; level <= TreeLod.PROPS.length; level++) { result.add(mesh.apply(level)); }
        while (result.size() > 1 && result.getLast() == result.get(result.size() - 2)) { result.removeLast(); }
        return result;
    }

    /**
     * The model of a model file's mesh under {@code key}, made once, and remembered as that file's
     * ({@link #modelFile}).
     */
    private Model fileModel(String key, String name, java.util.function.Supplier<Model> create) {
        Model known = models.get(key);
        if (known != null) { return known; }
        Model model = create.get();
        models.put(key, model);
        modelFiles.put(model, RigidGlb.source(root, name).file());
        return model;
    }

    /** The model file {@code model}'s mesh came from; null for a generated model. */
    File modelFile(Model model) { return modelFiles.get(model); }

    /**
     * The marked box a model that cannot load draws as, in every view, so a board with a missing or unreadable model
     * still loads. {@code asset} is logged once, and kept for the loading's report ({@link #takeMissing}) once per board.
     */
    private Model missing(String asset, String reason) {
        if (unloadable.putIfAbsent(asset, reason) == null) {
            LOGGER.warn("Board model {} draws as a marked box: {}", asset, reason);
        }
        if (!reported.contains(asset)) { unreported.putIfAbsent(asset, unloadable.get(asset)); }
        return box();
    }

    private static String reason(RuntimeException failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.toString();
    }

    /** The marked box, standing on its origin. Its material ({@link #MISSING}) stays a plain prop's. */
    private Model box() {
        return models.computeIfAbsent(MISSING, key -> {
            var builder = new com.badlogic.gdx.graphics.g3d.utils.ModelBuilder();
            builder.begin();
            com.badlogic.gdx.graphics.g3d.utils.shapebuilders.BoxShapeBuilder.build(builder.part(MISSING, GL20.GL_TRIANGLES,
                  com.badlogic.gdx.graphics.VertexAttributes.Usage.Position | com.badlogic.gdx.graphics.VertexAttributes.Usage.Normal,
                  new Material(MISSING, ColorAttribute.createDiffuse(1, .25f, .1f, 1))), 0, 0, 4, 8, 8, 8);
            return builder.end();
        });
    }

    /** The models that could not load and no report has shown yet, each with why; empty when there are none. */
    Map<String, String> takeMissing() {
        var result = Map.copyOf(unreported);
        reported.addAll(unreported.keySet());
        unreported.clear();
        return result;
    }

    /** A new board's report lists every model it draws as a marked box, whatever earlier boards reported. */
    void newBoard() {
        reported.clear();
        unreported.clear();
    }

    /**
     * The box of {@code instance}, a fresh placement of {@code model} with its nodes unchanged: its vertices scanned once
     * for every chunk build that places the model. The caller copies it.
     */
    BoundingBox bounds(Model model, ModelInstance instance) {
        return bounds.computeIfAbsent(model, unused -> instance.calculateBoundingBox(new BoundingBox()));
    }

    /**
     * Whether {@code file} exists, asked once per asset owner: every chunk build places the same models again, and a
     * file added while the board shows waits for the asset reload, which makes a fresh owner (GpuBattleView).
     */
    private boolean isFile(File file) { return files.computeIfAbsent(file, File::isFile); }

    /** A recolourable model's decoded levels, in its own colours; decoded once. */
    private List<ModelData> decoded(String name) {
        return recolourable.computeIfAbsent(name, shape -> decode(shape, RigidGlb.source(root, shape)));
    }

    /**
     * Starts decoding, on the common pool, the GLB of each named model (a board load's features) not decoded yet, so
     * the chunk builds that first draw them mostly upload. A generator's asset (BoardGenerator) is skipped; a name
     * without a model file, or a custom kit's building ({@link #building}), decodes to nothing. Levels a load never
     * takes are dropped at the next one.
     */
    void prepareModels(java.util.Set<String> names) {
        decoding.keySet().retainAll(names);
        File folder = root;
        for (String name : names) {
            if (modelLods.containsKey(name) || recolourable.containsKey(name) || decoding.containsKey(name)
                  || megamek.common.board.BoardGenerator.of(name) != null) { continue; }
            decoding.put(name, pooled(() -> {
                if (name.startsWith("buildings/") && TilesetScenery.customBuildingFile(name).isFile()) { return null; }
                var source = RigidGlb.source(folder, name);
                return source.file().isFile() ? RigidGlb.loadLods(source, folder.toPath()) : null;
            }));
        }
    }

    /** {@code name}'s levels from {@code source}: those {@link #prepareModels} decoded, else decoded now. */
    private List<ModelData> decode(String name, RigidGlb.Source source) {
        var started = decoding.remove(name);
        var levels = started == null ? null : joined(started);
        return levels != null ? levels : RigidGlb.loadLods(source, name.startsWith("buildings/") ? root.getParentFile().toPath() : root.toPath());
    }

    /** The first level whose mesh {@code level} draws: itself, or the level it repeats where the file lacks it. */
    private int ownLevel(String name, int level) {
        var levels = decoded(name);
        return levels.indexOf(levels.get(level));
    }

    /** A placed object's mesh per level, each {@link #slotted}: {@link #levels} for the placed objects' shared meshes. */
    List<Model> objectLevels(String name) {
        return propLevels(level -> slotted(name, level));
    }

    private record FlagKey(String id, BoardFlag artwork, BoardDecoration.Colours colours) {
        FlagKey(BoardDecoration object) { this(object.id(), object.flag(), object.colours()); }
    }
    private final Map<FlagKey, GpuFlag> flags = new HashMap<>();
    private record FlagPaint(BoardFlag artwork, BoardDecoration.Colours colours) { }
    private final Map<FlagPaint, Texture> flagPaints = new HashMap<>();

    Model flag(BoardDecoration object) {
        return flags.computeIfAbsent(new FlagKey(object), key -> new GpuFlag(flagPaints.computeIfAbsent(
              new FlagPaint(object.flag(), object.colours()), paint -> GpuFlag.texture(paint.artwork(), paint.colours())))).model;
    }

    /** The placed flag's cloth, null while no installed chunk draws it. */
    GpuFlag flagCloth(BoardDecoration object) { return flags.get(new FlagKey(object)); }

    private Model flagPreview() {
        return models.computeIfAbsent(BoardFlag.ASSET, key -> new Model(GpuFlag.data()));
    }

    private record BillboardShape(BoardBillboard billboard, float legs) { }
    private final Map<BillboardShape, List<Model>> billboards = new HashMap<>();

    /**
     * A billboard's levels of detail, shared by the placements of one shape and retired with the last that draws them
     * ({@link #retain}); the marked box, reported once per image, when its image cannot be read.
     */
    List<Model> billboardLevels(BoardBillboard billboard, float legs) {
        String art = BoardBillboard.ASSET + "@" + billboard.decals();
        if (unloadable.containsKey(art)) { return java.util.Collections.nCopies(RigidGlb.LEVELS, missing(art, unloadable.get(art))); }
        var shape = new BillboardShape(billboard, legs);
        var known = billboards.get(shape);
        if (known != null) { return known; }
        List<Model> levels = new ArrayList<>();
        try {
            for (int lod = 0; lod < BoardBillboardMesh.LODS; lod++) { levels.add(createModel(BoardBillboardMesh.data(billboard, legs, lod))); }
        } catch (RuntimeException failure) {
            levels.forEach(Model::dispose);
            return java.util.Collections.nCopies(RigidGlb.LEVELS, missing(art, reason(failure)));
        }
        while (levels.size() < RigidGlb.LEVELS) { levels.add(levels.getLast()); }
        billboards.put(shape, List.copyOf(levels));
        return billboards.get(shape);
    }

    /** The latest shapes placement previews asked for: a preview holds the newest, and sizing a brush makes more. */
    private final Map<String, Model> billboardGhosts = new java.util.LinkedHashMap<>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Model> eldest) {
            if (size() <= 8) { return false; }
            eldest.getValue().dispose();
            return true;
        }
    };

    /** A blank billboard of this shape for a placement preview, kept apart from the chunks' cache as it outlives their commits. */
    Model billboardGhost(BoardBillboard billboard, float legs) {
        var blank = billboard.blank();
        return billboardGhosts.computeIfAbsent(blank + "@" + legs, key -> createModel(BoardBillboardMesh.data(blank, legs, 0)));
    }

    private Model billboardPreview() {
        return models.computeIfAbsent(BoardBillboard.ASSET, key -> createModel(BoardBillboardMesh.preview()));
    }

    /**
     * A placed object's model, in every view: a model with colour slots is one shared mesh whose placements pass their
     * colours per draw ({@link RigidGlb#colourSlotMesh}, applied by model-colour-slots.glsl); another model is the model
     * itself, a missing or unreadable file the marked box ({@link #missing}).
     */
    Model slotted(String name) { return slotted(name, 0); }

    /** {@link #slotted(String)} at a mesh level ({@link #objectLevels}). */
    Model slotted(String name, int level) {
        if (BoardFlag.ASSET.equals(name)) { return flagPreview(); }
        if (BoardBillboard.ASSET.equals(name)) { return billboardPreview(); }
        var source = RigidGlb.source(root, name);
        if (!isFile(source.file())) { return missing(name, "no model file " + source.file().getPath()); }
        if (unloadable.containsKey(name)) { return missing(name, unloadable.get(name)); }
        try {
            if (!hasSlots.computeIfAbsent(name, key -> !RigidGlb.colourSlots(source.file()).isEmpty())) {
                return lodModel(name, level);
            }
            int own = ownLevel(name, level);
            // The key cannot meet a detail level's name or a colour copy's "name[...]".
            return fileModel((own == 0 ? name : MeshLod.name(name, own)) + "#slots", name,
                  () -> createModel(RigidGlb.colourSlotMesh(decoded(name).get(own))));
        } catch (RuntimeException failure) { return missing(name, reason(failure)); }
    }

    /**
     * A placed model in its colours as one model of its own ({@link #model(String,
     * megamek.common.board.BoardDecoration.Colours)}), for the editor preview's ghosts; a missing or unreadable file is
     * the marked box, so missing authored references remain editable and visible instead of aborting the board renderer.
     */
    Model decoration(String name, megamek.common.board.BoardDecoration.Colours colours) {
        if (BoardFlag.ASSET.equals(name)) { return flagPreview(); }
        if (BoardBillboard.ASSET.equals(name)) { return billboardPreview(); }
        var file = RigidGlb.source(root, name).file();
        return isFile(file) ? model(name, colours) : missing(name, "no model file " + file.getPath());
    }

    /**
     * A decal's image by path convention ({@link megamek.common.board.BoardDecalArt#image}): one mipmapped texture per
     * image, clamped at its edges, so no neighbour's texels bleed into any mip level (paint draws are grouped per image
     * by {@code Layer.paintGroups} instead). Its mip levels are averaged by alpha ({@link #paintTexture}).
     */
    Texture decorationPaint(String asset) {
        File file = megamek.common.board.BoardDecalArt.image(asset);
        if (!isFile(file)) { return null; }
        return materials.computeIfAbsent(file.toPath().toAbsolutePath().normalize().toString(), key -> paintTexture(file));
    }

    /** Mip levels averaged by alpha ({@link GpuColorTexture#cutout}), so multi-coloured edges keep their colour at distance. */
    private static Texture paintTexture(File file) {
        Texture texture = GpuColorTexture.cutout(() -> GpuColorTexture.cutoutLevels(GpuColorTexture.decode(new FileHandle(file))));
        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        // Ground paint is seen at grazing angles; keep its lines sharp along the view direction.
        texture.setAnisotropicFilter(8);
        return texture;
    }

    /**
     * Custom kits override the tileset path for buildings, fuel tanks and industrial structures; every key that resolves
     * to one kit (TilesetScenery#customBuildingFile, a generated kit's footprint) shares its meshes. {@code reach}:
     * {@link ConnectedKit#reach}.
     */
    GpuBuilding.Assembly building(String asset, int levels, int[] reach, long seed, boolean withInterior) {
        return building(asset, levels, reach, seed, withInterior ? megamek.common.board.BuildingInteriors.YES : megamek.common.board.BuildingInteriors.NO);
    }

    GpuBuilding.Assembly building(String asset, int levels, int[] reach, long seed, megamek.common.board.BuildingInteriors interiors) {
        return building(asset, levels, reach, seed, interiors, false);
    }

    GpuBuilding.Assembly building(String asset, int levels, int[] reach, long seed,
          megamek.common.board.BuildingInteriors interiors, boolean stepped) {
        return building(asset, levels, reach, seed, interiors, stepped, 1);
    }

    GpuBuilding.Assembly building(String asset, int levels, int[] reach, long seed,
          megamek.common.board.BuildingInteriors interiors, boolean stepped, float footprintScale) {
        return building(asset, levels, reach, seed, interiors, stepped, footprintScale, null);
    }

    /** {@code corners}: {@link ConnectedKit#corners}, for its walls' junctions with the neighbours' (null: none). */
    GpuBuilding.Assembly building(String asset, int levels, int[] reach, long seed,
          megamek.common.board.BuildingInteriors interiors, boolean stepped, float footprintScale, int[] corners) {
        return building(asset, levels, reach, seed, interiors, stepped, footprintScale, corners, List.of());
    }

    /** {@code doors}: the hex's large doors ({@link KitDoors#cuts}). */
    GpuBuilding.Assembly building(String asset, int levels, int[] reach, long seed,
          megamek.common.board.BuildingInteriors interiors, boolean stepped, float footprintScale, int[] corners,
          List<KitDoors.Cut> doors) {
        if (!asset.startsWith("buildings/") || BoardWall.supports(asset)) { return null; }
        File file = TilesetScenery.customBuildingFile(asset);
        // A kit that cannot load is reported under its own name; the caller then draws the asset's model, or its box.
        String kit = asset + " (kit)";
        if (!isFile(file)) { return null; }
        if (unloadable.containsKey(kit)) { missing(kit, unloadable.get(kit)); return null; }
        try {
            return buildings.computeIfAbsent(GpuBuilding.kit(asset, footprintScale), key -> new GpuBuilding(key, this::createModel))
                  .assemble(levels, reach, seed, interiors, stepped, corners, doors);
        } catch (RuntimeException failure) {
            missing(kit, reason(failure));
            return null;
        }
    }

    /**
     * What each terrain sharing these assets draws: its installed chunks at its last commit, plus whatever its builds
     * took since ({@link #take}) and have not installed yet.
     */
    private record Live(java.util.Set<GpuBuilding.Assembly> buildings, java.util.Set<Model> models) { }
    private final Map<Object, Live> users = new java.util.IdentityHashMap<>();

    /**
     * Installs one kit level's fragments that finished cutting, if any (GpuBuilding#installFragments), and says whether it
     * did: render thread, between falls, so no fall's frame pays for it.
     */
    boolean installFragments() {
        for (GpuBuilding building : buildings.values()) {
            if (building.installFragments()) { return true; }
        }
        return false;
    }

    /** The installed fragments' bytes, every kit's: for reports. */
    long fragmentBytes() { return buildings.values().stream().mapToLong(GpuBuilding::fragmentBytes).sum(); }

    /** Whether a kit level is still being broken into fragments, or not installed yet: for tests. */
    boolean fragmentsPending() { return buildings.values().stream().anyMatch(GpuBuilding::fragmentsPending); }

    /**
     * Records the generated buildings, industrial plants and rail and maglev routes that {@code user}'s installed chunks draw,
     * then retires those no user of these assets draws or is still building with. On the GL thread, at a scene commit,
     * when {@code user} has no chunk build in flight.
     */
    void retain(Object user, java.util.Set<GpuBuilding.Assembly> buildings, java.util.Set<Model> industrial, java.util.Set<Model> routes) {
        java.util.Set<Model> models = new java.util.HashSet<>(industrial);
        models.addAll(routes);
        users.put(user, new Live(new java.util.HashSet<>(buildings), models));
        java.util.Set<GpuBuilding.Assembly> liveBuildings = new java.util.HashSet<>();
        java.util.Set<Model> liveModels = new java.util.HashSet<>();
        for (Live live : users.values()) { liveBuildings.addAll(live.buildings()); liveModels.addAll(live.models()); }
        this.buildings.values().forEach(building -> building.retain(liveBuildings));
        retain(this.industrial, liveModels);
        multiHexes.values().removeIf(share -> {
            if (share.levels().stream().anyMatch(liveModels::contains) || liveModels.contains(share.glass())) { return false; }
            dispose(share);
            return true;
        });
        retain(this.routes, liveModels);
        billboards.values().removeIf(levels -> {
            if (levels.stream().anyMatch(liveModels::contains)) { return false; }
            levels.stream().distinct().forEach(Model::dispose);
            return true;
        });
        flags.values().removeIf(flag -> {
            if (liveModels.contains(flag.model)) { return false; }
            flag.model.dispose(); return true;
        });
        var livePaints = new java.util.HashSet<Texture>();
        flags.values().forEach(flag -> livePaints.add(flag.paint));
        flagPaints.values().removeIf(paint -> {
            if (livePaints.contains(paint)) { return false; }
            paint.dispose(); return true;
        });
        interiors.entrySet().removeIf(entry -> {
            if (liveModels.contains(entry.getKey().shell()) || this.models.containsValue(entry.getKey().shell())) { return false; }
            entry.getValue().dispose(); return true;
        });
        authoredBuildings.values().removeIf(slice -> {
            if (slice.levels().stream().anyMatch(liveModels::contains) || liveModels.contains(slice.interior())) { return false; }
            slice.dispose(); return true;
        });
    }

    /**
     * Keeps what one of {@code user}'s chunk builds took until {@code user}'s own next {@link #retain}, so another
     * user's commit cannot retire it between collection and installation.
     */
    void take(Object user, GpuBuilding.Assembly building, Model model) {
        Live live = users.computeIfAbsent(user, key -> new Live(new java.util.HashSet<>(), new java.util.HashSet<>()));
        if (building != null) { live.buildings().add(building); }
        if (model != null) { live.models().add(model); }
    }

    /** A disposed borrower draws nothing; the next {@link #retain} retires what only it drew. */
    void release(Object user) { users.remove(user); }

    Model industrial(BoardIndustrial.Layout layout) {
        return industrial.computeIfAbsent(layout, key -> {
            // Textures are asset-owned; decode before allocating native mesh buffers.
            Texture paint = material("structures/industrial/paint"), steel = material("structures/industrial/steel");
            // Their planar repeats widen with their textures (GpuMaterialCatalog); the fan is fitted to its opening.
            var data = BoardIndustrial.model(key, coverage("structures/industrial/paint"), coverage("structures/industrial/steel"));
            Texture fan = data.materials.size > 2 ? material("structures/industrial/fan") : null;
            Model model = new Model(data);
            for (var surface : model.materials) {
                // An opaque diffuse material with its finish stays eligible for the existing prop batch.
                String name = surface.id.equals("fan") ? "structures/industrial/fan"
                      : surface.id.equals("paint") ? "structures/industrial/paint" : "structures/industrial/steel";
                surface.set(TextureAttribute.createDiffuse(surface.id.equals("fan") ? fan
                      : surface.id.equals("paint") ? paint : steel));
                finish(name, surface);
            }
            return model;
        });
    }

    /** Procedural models belong to this renderer and survive while installed or cached chunks use them. */
    Model route(BoardRail.Layout layout) {
        return routes.computeIfAbsent(layout, key -> new Model(BoardRail.model(key)));
    }

    private static void retain(Map<?, Model> models, java.util.Set<Model> live) {
        models.values().removeIf(model -> {
            if (live.contains(model)) { return false; }
            model.dispose();
            return true;
        });
    }

    private Model createModel(ModelData data) {
        return ModelTextures.create(data, materials, (filename, color) -> texture(new FileHandle(filename), color));
    }

    Model lodModel(String name, int level) {
        if (BoardFlag.ASSET.equals(name)) { return flagPreview(); }
        if (BoardBillboard.ASSET.equals(name)) { return billboardPreview(); }
        if (BoardWall.supports(name)) { return wallLevels(name, 1).get(level); }
        if (BoardMultiHexBuilding.supports(name)) { return multiHexLevels(BoardMultiHexBuilding.layout(name, 1)).get(level); }
        if (unloadable.containsKey(name)) { return missing(name, unloadable.get(name)); }
        try {
            return modelLods.computeIfAbsent(name, shape -> {
                // A tree's winter form may be its bare file's snow variant.
                var source = RigidGlb.source(root, shape);
                if (!source.file().isFile()) {
                    throw new IllegalArgumentException("no model file " + source.file().getPath());
                }
                var data = decode(shape, source);
                // A file without near geometry is no model: its draws (tree stands, prop batches) need a mesh.
                if (data.isEmpty() || data.getFirst().meshes.isEmpty()) {
                    throw new IllegalArgumentException("no geometry in " + source.file().getPath());
                }
                List<Model> levels = new ArrayList<>();
                for (int index = 0; index < data.size(); index++) {
                    int previous = data.indexOf(data.get(index));
                    levels.add(previous < index ? levels.get(previous)
                          : fileModel(MeshLod.name(shape, index), shape, () -> createModel(data.get(previous))));
                }
                return List.copyOf(levels);
            }).get(level);
        } catch (RuntimeException failure) { return missing(name, reason(failure)); }
    }

    /** A share building's share of one hex (BoardMultiHexBuilding), cached by its layout as a wall's mesh is. */
    List<Model> multiHexLevels(BoardMultiHexBuilding.Layout layout) {
        var share = share(layout);
        return share != null ? share.levels()
              : java.util.Collections.nCopies(RigidGlb.LEVELS, missing(layout.design().asset, unloadable.get(layout.design().asset)));
    }

    /** A share building share's glass ({@link BoardMultiHexBuilding#glass}), with its levels; null without any. */
    Model multiHexGlass(BoardMultiHexBuilding.Layout layout) {
        var share = share(layout);
        return share == null ? null : share.glass();
    }

    /** The share's models, made on first use; null (reported once) when its design cannot be built. */
    private MultiHex share(BoardMultiHexBuilding.Layout layout) {
        String asset = layout.design().asset;
        if (unloadable.containsKey(asset)) { return null; }
        var share = multiHexes.get(layout);
        if (share != null) { return share; }
        List<Model> levels = new ArrayList<>();
        Model glass = null;
        try {
            for (int lod = 0; lod < BoardMultiHexBuilding.LODS; lod++) { levels.add(createModel(BoardMultiHexBuilding.model(layout, lod))); }
            var panes = BoardMultiHexBuilding.glass(layout);
            glass = panes == null ? null : createModel(panes);
        } catch (RuntimeException failure) {
            levels.forEach(Model::dispose);
            missing(asset, reason(failure));
            return null;
        }
        while (levels.size() < RigidGlb.LEVELS) { levels.add(levels.getLast()); }
        share = new MultiHex(List.copyOf(levels), glass);
        multiHexes.put(layout, share);
        return share;
    }

    private static void dispose(MultiHex share) {
        share.levels().stream().distinct().forEach(Model::dispose);
        if (share.glass() != null) { share.glass().dispose(); }
    }

    /** Two authored component levels; remaining slots reuse the far mesh, like the ordinary rigid model loader. */
    List<Model> wallLevels(String asset, int levels) {
        return wallLevels(BoardWall.layout(asset, levels));
    }

    /** The captured wall layout determines the shared meshes; this asset owner's ordinary cache disposes them. */
    List<Model> wallLevels(BoardWall.Layout layout) {
        String shape = layout.asset() + "@family-" + layout.family().folder + "@height-" + layout.height() + "@arms-" + layout.arms();
        // A family's kit that cannot load is reported once by its name; its walls draw the marked box.
        String kit = "buildings/walls/" + layout.family().folder;
        if (unloadable.containsKey(kit)) { return java.util.Collections.nCopies(RigidGlb.LEVELS, missing(kit, unloadable.get(kit))); }
        try {
            return modelLods.computeIfAbsent(shape, ignored -> {
                List<Model> result = new ArrayList<>();
                for (int lod = 0; lod < BoardWall.LODS; lod++) {
                    int own = lod;
                    result.add(models.computeIfAbsent(MeshLod.name(shape, lod),
                          key -> createModel(BoardWall.model(layout, own))));
                }
                while (result.size() < RigidGlb.LEVELS) { result.add(result.getLast()); }
                return List.copyOf(result);
            });
        } catch (RuntimeException failure) {
            return java.util.Collections.nCopies(RigidGlb.LEVELS, missing(kit, reason(failure)));
        }
    }

    /** A board material's colour: albedo with its coverage, or with height for sculpted terrain. */
    Texture material(String name) {
        return texture(name, GpuMaterialMaps.colorLayout(name), true);
    }

    /** A board material's normal map in the board's layout (GpuMaterialMaps.normalLayout). */
    Texture materialNormal(String name) {
        return texture(name, GpuMaterialMaps.normalLayout(name), false);
    }

    /**
     * Gives a draw of a board material's own (a road, a tile, an industrial or decoration material) the material's whole
     * finish, as a GLB material whose base colour is its albedo has it (RigidGlb): GpuModelMaterial, its normal and
     * occlusion maps, the emission map as the emissive texture and the heightmap as the bump texture.
     */
    Material finish(String name, Material material) {
        var finish = GpuMaterialMaps.modelFinish(definition(name));
        material.set(new GpuModelMaterial(finish.roughness(), finish.metallic(), finish.scatter(),
              finish.map() == null ? null : texture(name, GpuMaterialMaps.Layout.METALLIC_ROUGHNESS, false), finish.tint(),
              finish.glow(), true, new float[] { finish.depthU(), finish.depthV(), finish.minimumLayers(),
                    finish.maximumLayers() }));
        if (finish.emission() != null) {
            material.set(TextureAttribute.createEmissive(texture(new FileHandle(finish.emission()), true)));
        }
        GodotMaterial board = definition(name);
        if (board.normal() != null) {
            material.set(TextureAttribute.createNormal(texture(name, GpuMaterialMaps.Layout.NORMAL, false)));
        }
        if (board.occlusion() != null) {
            material.set(TextureAttribute.createAmbient(texture(name, GpuMaterialMaps.Layout.OCCLUSION, false)));
        }
        // The heightmap is the material's bump texture (GpuModelMaterial).
        if (finish.heightmap() != null) {
            material.set(TextureAttribute.createBump(texture(name, GpuMaterialMaps.Layout.HEIGHT, false)));
        }
        return material;
    }

    /** The albedo image of a board material, for code that composes its own textures from it. */
    FileHandle albedo(String name) {
        FileHandle file = new FileHandle(definition(name).albedo());
        requireFiles(List.of(file));
        return file;
    }

    Texture scatter() {
        return materials.computeIfAbsent(SCATTER, key -> {
            var started = scatterPixels;
            scatterPixels = null;
            Pixmap pixels = started != null ? joined(started) : GpuScatter.pixels(GpuScatter.materials(this));
            try {
                return GpuScatter.atlas(pixels);
            } finally { pixels.dispose(); }
        });
    }

    /** The scatter atlas's key among the materials. */
    private static final String SCATTER = "scatter-atlas";

    /** The scatter atlas's pixels {@link #prepareTerrainArrays} started, which {@link #scatter} takes; or null. */
    private CompletableFuture<Pixmap> scatterPixels;

    Cliff road(String name) {
        return cliffs.computeIfAbsent("roads/" + name, key -> new Cliff(texture(key, GpuMaterialMaps.Layout.COLOR, true),
              texture(key, GpuMaterialMaps.Layout.NORMAL, false), texture(key, GpuMaterialMaps.Layout.RELIEF, false)));
    }

    /**
     * Each road material's heightmap parallax in {@link GpuRoads#MATERIALS} order (GpuMaterialMaps.ModelFinish): depth
     * along U and V, and the deep parallax's minimum and maximum layers; zero depth without a heightmap.
     */
    float[] roadRelief() {
        if (roadRelief != null) { return roadRelief; }
        float[] relief = new float[GpuRoads.MATERIALS.size() * 4];
        for (int index = 0; index < GpuRoads.MATERIALS.size(); index++) {
            var finish = GpuMaterialMaps.modelFinish(definition("roads/" + GpuRoads.MATERIALS.get(index)));
            if (finish.heightmap() == null) { continue; }
            relief[index * 4] = finish.depthU();
            relief[index * 4 + 1] = finish.depthV();
            relief[index * 4 + 2] = finish.minimumLayers();
            relief[index * 4 + 3] = finish.maximumLayers();
        }
        roadRelief = relief;
        return relief;
    }

    /** One complete sculpt material and its repeat scale. */
    Sculpt sculpt(String name) {
        return sculpts.computeIfAbsent(name, key -> new Sculpt(material(sculptName(key)), materialNormal(sculptName(key)),
              sculptTile(key)));
    }

    /**
     * The metres one repeat of a sculpted-terrain material covers: its catalogued repeat at its catalogued width, widened
     * with the texture ({@link #coverage}). Array-backed materials need it without uploading a second set of 2D textures.
     */
    float sculptTile(String name) {
        String material = sculptName(name);
        definition(material);
        float metres = catalog().loose(material).metres();
        if (Float.isNaN(metres)) { throw new IllegalArgumentException("A sculpted material needs its catalogued metres: " + name); }
        return metres * coverage(material);
    }

    private static void requireFiles(List<FileHandle> files) {
        for (FileHandle file : files) {
            if (!file.exists()) {
                throw new IllegalArgumentException("Missing required Enhanced terrain asset: " + file);
            }
        }
    }

    /** Original rim lightness mask for drops up to two levels, without normalization or generated normals. */
    BoardScene.Pixels inclineMask() {
        if (incline == null) {
            incline = loadRimMask("terrain/classic/incline-dark");
        }
        return incline;
    }

    /** The coarser lightness mask of a drop above two levels, the board's own high-incline split. */
    BoardScene.Pixels highInclineMask() {
        if (highIncline == null) {
            highIncline = loadRimMask("terrain/classic/high-incline-dark");
        }
        return highIncline;
    }

    private BoardScene.Pixels loadRimMask(String asset) {
        try {
            return new BoardScene.Pixels(ImageIO.read(albedo(asset).file()));
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot load the cliff-top rim mask", error);
        }
    }

    /**
     * A board material's packed layout as a texture, keyed by its main map so a model naming that map shares it
     * (GpuMaterialMaps.modelMap).
     */
    private Texture texture(String name, GpuMaterialMaps.Layout layout, boolean color) {
        return texture(new FileHandle(GpuMaterialMaps.primary(definition(name), layout)), color,
              GpuMaterialMaps.cacheSuffix(layout), packed(name, layout));
    }

    private Texture texture(FileHandle file, boolean color) { return texture(file, color, "", null); }

    private Texture texture(FileHandle file, boolean color, String suffix, Supplier<Pixmap> pixels) {
        return materials.computeIfAbsent((color ? "color:" : "data:") + file.file().toPath().toAbsolutePath().normalize() + suffix, key -> {
            Texture texture = pixels != null ? GpuColorTexture.create(pixels, true, color)
                  : color ? GpuColorTexture.create(file, true) : GpuColorTexture.createData(file, true);
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            boolean repeating = file.file().toPath().toAbsolutePath().normalize()
                  .startsWith(new File(root, "textures").toPath().toAbsolutePath().normalize());
            Texture.TextureWrap wrap = repeating ? Texture.TextureWrap.Repeat : Texture.TextureWrap.ClampToEdge;
            texture.setWrap(wrap, wrap);
            if (repeating && (file.parent().name().equals("surfaces") || file.parent().name().equals("roads")
                  || file.parent().name().equals("ice") || file.parent().name().equals("industrial")
                  || file.parent().name().equals("lunar")
                  || file.parent().name().equals("common") && file.name().startsWith("matte-steel_"))) {
                // Detailed materials retain full resolution; mipmaps and supported anisotropy handle distance.
                texture.setAnisotropicFilter(8);
            } else if (repeating) {
                int level = Math.max(0, (int) Math.ceil(Math.log(Math.max(texture.getWidth(), texture.getHeight()) / 128.0) / Math.log(2)));
                texture.bind();
                // Match the board artwork's texel density while retaining editable source images.
                Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_BASE_LEVEL, level);
            }
            return texture;
        });
    }

    Texture liquid(BoardLiquid.Textures source, float time) {
        return liquidAnimation(source).at(time);
    }

    Animation<Texture> liquidAnimation(BoardLiquid.Textures source) {
        return liquids.computeIfAbsent(source, this::loadLiquid);
    }

    /** A file of the Classic tileset, such as a liquid animation the board plays and the minimap shows. */
    static File tilesetFile(String path) {
        return new File(Configuration.dataDir(), "models/board/classic/" + path);
    }

    private Animation<Texture> loadLiquid(BoardLiquid.Textures source) {
        Animation<BoardScene.Pixels> decoded = readWater(tilesetFile(source.base()));
        Animation<BoardScene.Pixels> foam = source.foam().isEmpty() ? null
              : readAnimation(tilesetFile(source.foam()), false);
        if (foam != null && !Arrays.equals(decoded.ends(), foam.ends())) {
            throw new IllegalStateException("Liquid and foam GIF timelines must match: " + source);
        }
        List<Texture> frames = new ArrayList<>();
        try {
            for (int index = 0; index < decoded.frames().size(); index++) {
                BoardScene.Pixels image = decoded.frames().get(index);
                BoardScene.Pixels overlay = foam == null ? null : foam.frames().get(index);
                if (overlay != null && (overlay.width() != image.width() || overlay.height() != image.height())) {
                    throw new IllegalStateException("Liquid and foam GIF dimensions must match: " + source);
                }
                var pixels = new float[image.width() * image.height() * 4];
                for (int pixel = 0; pixel < image.width() * image.height(); pixel++) {
                    int base = image.rgba(pixel), foamPixel = overlay == null ? 0 : overlay.rgba(pixel);
                    float alpha = (foamPixel & 255) / 255f;
                    for (int shift = 24; shift >= 8; shift -= 8) {
                        pixels[pixel * 4 + (24 - shift) / 8] = GpuColor.linear((base >>> shift & 255) / 255f);
                    }
                    // A themed water takes its tone before its foam, which stays white.
                    if (source.tone() != null) { tone(pixels, pixel * 4, source.tone()); }
                    for (int shift = 24; shift >= 8; shift -= 8) {
                        int at = pixel * 4 + (24 - shift) / 8;
                        pixels[at] += (GpuColor.linear((foamPixel >>> shift & 255) / 255f) - pixels[at]) * alpha;
                    }
                    pixels[pixel * 4 + 3] = (base & 255) / 255f;
                }
                Texture texture = GpuColorTexture.ldr(pixels, image.width(), image.height(), true);
                texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
                texture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
                frames.add(texture);
            }
            return new Animation<>(List.copyOf(frames), decoded.ends(), decoded.duration());
        } catch (RuntimeException error) {
            frames.forEach(Texture::dispose);
            throw error;
        }
    }

    /** A themed water's pixel at {@code at}: the plain water's linear luminance in its {@link BoardLiquid.Tone}. */
    private static void tone(float[] pixels, int at, BoardLiquid.Tone tone) {
        float luminance = .2126f * pixels[at] + .7152f * pixels[at + 1] + .0722f * pixels[at + 2];
        pixels[at] = Math.min(1, luminance * tone.red());
        pixels[at + 1] = Math.min(1, luminance * tone.green());
        pixels[at + 2] = Math.min(1, luminance * tone.blue());
    }

    static Animation<BoardScene.Pixels> readWater(File file) {
        return readAnimation(file, true);
    }

    /** Compose optimized GIF patches with their disposal and timing; foam retains its transparent coverage. */
    static Animation<BoardScene.Pixels> readAnimation(File file, boolean fillEdges) {
        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream input = ImageIO.createImageInputStream(file)) {
            reader.setInput(input);
            var stream = reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0");
            var screen = child(stream, "LogicalScreenDescriptor");
            int width = attribute(screen, "logicalScreenWidth"), height = attribute(screen, "logicalScreenHeight");
            BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int count = reader.getNumImages(true);
            List<BoardScene.Pixels> frames = new ArrayList<>();
            float[] ends = new float[count];
            float duration = 0;
            for (int index = 0; index < count; index++) {
                var metadata = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0");
                var control = child(metadata, "GraphicControlExtension");
                var descriptor = child(metadata, "ImageDescriptor");
                String disposal = control.getAttributes().getNamedItem("disposalMethod").getNodeValue();
                int left = attribute(descriptor, "imageLeftPosition"), top = attribute(descriptor, "imageTopPosition");
                BufferedImage patch = reader.read(index);
                BufferedImage previous = null;
                if (disposal.equals("restoreToPrevious")) {
                    previous = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                    previous.setData(canvas.getData());
                }
                Graphics2D graphics = canvas.createGraphics();
                try {
                    graphics.drawImage(patch, left, top, null);
                    frames.add(fillEdges ? waterFrame(canvas) : new BoardScene.Pixels(canvas));
                    if (disposal.equals("restoreToBackgroundColor")) {
                        graphics.setComposite(AlphaComposite.Clear);
                        graphics.fillRect(left, top, patch.getWidth(), patch.getHeight());
                    }
                } finally {
                    graphics.dispose();
                }
                if (previous != null) {
                    canvas = previous;
                }
                duration += Math.max(0.02f, attribute(control, "delayTime") / 100f);
                ends[index] = duration;
            }
            return new Animation<>(List.copyOf(frames), ends, duration);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Cannot decode board liquid animation " + file, exception);
        } finally {
            reader.dispose();
        }
    }

    /** The mesh defines the shoreline; repeating hex-shaped alpha would pinch a scrolling waterfall. */
    private static BoardScene.Pixels waterFrame(BufferedImage canvas) {
        BufferedImage frame = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_ARGB);
        List<Integer> filledRows = new ArrayList<>();
        for (int y = 0; y < canvas.getHeight(); y++) {
            int left = 0, right = canvas.getWidth() - 1;
            while (left < right && (canvas.getRGB(left, y) >>> 24) == 0) {
                left++;
            }
            while (right > left && (canvas.getRGB(right, y) >>> 24) == 0) {
                right--;
            }
            if ((canvas.getRGB(left, y) >>> 24) == 0) { continue; }
            filledRows.add(y);
            int color = canvas.getRGB(left, y);
            for (int x = 0; x < canvas.getWidth(); x++) {
                int pixel = canvas.getRGB(Math.max(left, Math.min(right, x)), y);
                // Mars water dithers transparency inside the hex too; material opacity replaces that 2D mask.
                if ((pixel >>> 24) != 0) { color = pixel; }
                frame.setRGB(x, y, color);
            }
        }
        if (filledRows.isEmpty()) { throw new IllegalArgumentException("Empty liquid image"); }
        // Some themed GIFs also have a completely transparent row above or below the hex.
        for (int y = 0; y < canvas.getHeight(); y++) {
            if ((frame.getRGB(0, y) >>> 24) != 0) { continue; }
            int nearest = filledRows.getFirst();
            for (int row : filledRows) {
                if (Math.abs(row - y) < Math.abs(nearest - y)) { nearest = row; }
            }
            for (int x = 0; x < canvas.getWidth(); x++) { frame.setRGB(x, y, frame.getRGB(x, nearest)); }
        }
        return new BoardScene.Pixels(frame);
    }

    /** Interior meshes share the shell's local coordinates; geometry tuning only changes their instance transform. */
    Model interior(String asset, int levels) {
        return interior(model(asset), levels);
    }

    Model interior(Model shell, int levels) {
        return interiors.computeIfAbsent(new Interior(shell, levels),
              key -> GpuBuildingInterior.build(key.shell(), key.levels()));
    }

    private static org.w3c.dom.Node child(org.w3c.dom.Node parent, String name) {
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeName().equals(name)) {
                return node;
            }
        }
        throw new IllegalArgumentException("Missing GIF metadata: " + name);
    }

    private static int attribute(org.w3c.dom.Node node, String name) {
        return Integer.parseInt(node.getAttributes().getNamedItem(name).getNodeValue());
    }

    @Override
    public void dispose() {
        routes.values().forEach(Model::dispose);
        routes.clear();
        industrial.values().forEach(Model::dispose);
        industrial.clear();
        multiHexes.values().forEach(GpuAssets::dispose);
        multiHexes.clear();
        authoredBuildings.values().forEach(GpuAuthoredBuilding.Slice::dispose);
        authoredBuildings.clear(); GpuAuthoredBuilding.reload();
        buildings.values().forEach(GpuBuilding::dispose);
        buildings.clear();
        interiors.values().forEach(Model::dispose);
        interiors.clear();
        models.values().forEach(Model::dispose);
        billboards.values().forEach(levels -> levels.stream().distinct().forEach(Model::dispose)); billboards.clear();
        billboardGhosts.values().forEach(Model::dispose); billboardGhosts.clear();
        flags.values().forEach(flag -> flag.model.dispose()); flags.clear();
        flagPaints.values().forEach(Texture::dispose); flagPaints.clear();
        materials.values().forEach(Texture::dispose);
        liquids.values().forEach(animation -> animation.frames().forEach(Texture::dispose));
        models.clear();
        modelFiles.clear();
        plainLevels.clear();
        modelLods.clear();
        decoding.clear();
        materials.clear();
        cliffs.clear();
        lavaEmission = null;
        sculpts.clear();
        definitions.clear();
        coverages.clear();
        catalog = null;
        if (terrainArrays != null) { terrainArrays.arrays().dispose(); terrainArrays = null; }
        if (preparing != null) { preparing.discard(); preparing = null; }
        if (scatterPixels != null) { scatterPixels.thenAccept(Pixmap::dispose); scatterPixels = null; }
        if (fullSizeMaps != null) { fullSizeMaps.dispose(); fullSizeMaps = null; }
        roadRelief = null;
        if (roadArray != null) { roadArray.dispose(); roadArray = null; }
        liquids.clear();
        users.clear();
        incline = null;
        highIncline = null;
    }
}
