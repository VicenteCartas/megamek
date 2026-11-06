/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMaterial;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMesh;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMeshPart;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNode;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNodePart;
import com.badlogic.gdx.graphics.g3d.model.data.ModelTexture;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ByteArray;
import com.badlogic.gdx.utils.FloatArray;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.javagl.jgltf.model.AccessorByteData;
import de.javagl.jgltf.model.AccessorData;
import de.javagl.jgltf.model.AccessorFloatData;
import de.javagl.jgltf.model.AccessorIntData;
import de.javagl.jgltf.model.AccessorModel;
import de.javagl.jgltf.model.AccessorShortData;
import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.MaterialModel;
import de.javagl.jgltf.model.MeshModel;
import de.javagl.jgltf.model.MeshPrimitiveModel;
import de.javagl.jgltf.model.NodeModel;
import de.javagl.jgltf.model.TextureModel;
import de.javagl.jgltf.model.io.GltfAssetReader;
import de.javagl.jgltf.model.io.v2.GltfAssetV2;
import de.javagl.jgltf.model.v2.GltfModelCreatorV2;
import de.javagl.jgltf.model.v2.MaterialModelV2;
import megamek.common.board.BoardDecoration;

/**
 * CPU-only importer for the rigid GLB kits. JglTF decodes buffers/accessors; this adapter preserves named parts
 * and converts Y-up glTF data into the board's Z-up ModelData, preserving linear colors. Textures and animation remain
 * owned by the existing renderer. Unsupported asset features fail before any graphics resources are allocated.
 */
final class RigidGlb {
    static final int STRIDE = 12;
    /** The shared recolourable mesh's extra vertex attribute ({@link #colourSlotMesh}). */
    static final String COLOUR_SLOT = "a_colourSlot";
    /** A placement passes its colours as one vec4 (model-colour-slots.glsl): one float per slot, -1 for its own. */
    private static final float[] OWN_COLOURS = { -1, -1, -1, -1 };

    static {
        require(BoardDecoration.Colours.SLOTS == OWN_COLOURS.length, "A placement's colours are one vec4 of slots");
    }
    private static final Matrix4 Z_UP = new Matrix4(new float[] {
          1, 0, 0, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1 });
    private static final Matrix4 Y_UP = new Matrix4(Z_UP).tra();

    private RigidGlb() { }

    /** Encoded bytes are retained for managed GPU texture recreation; external files keep a shared path key. */
    record Image(String key, byte[] encoded, String file, int minFilter, int magFilter, int wrapS, int wrapT) { }

    /**
     * A finite night light: file positions are glTF Y-up; decoded positions use the board's Z-up model units. With an
     * {@code end} it is one emitter spread along that segment, as a whole row of lamps.
     */
    record NightLight(Vector3 position, Vector3 end, Vector3 color, float power, float size) {
        NightLight(Vector3 position, Vector3 color, float power, float size) { this(position, null, color, power, size); }
    }

    static List<NightLight> nightLights(File file) {
        try {
            Object extras = document(file).get("extras");
            Object lights = extras instanceof Map<?, ?> values ? values.get("mmNightLights") : null;
            if (lights == null) { return List.of(); }
            require(lights instanceof List<?>, "mmNightLights must be a list");
            List<NightLight> result = new ArrayList<>();
            for (Object value : (List<?>) lights) {
                require(value instanceof Map<?, ?>, "Night light must be an object");
                var light = (Map<?, ?>) value;
                Vector3 position = lightVector(light.get("position")).mul(Z_UP);
                Vector3 color = lightVector(light.get("color"));
                float power = lightNumber(light.get("power")), size = lightNumber(light.get("size"));
                require(power > 0 && size > 0 && color.x >= 0 && color.y >= 0 && color.z >= 0,
                      "Night light needs positive power/size and nonnegative linear RGB");
                result.add(new NightLight(position, color, power, size));
            }
            return List.copyOf(result);
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot read night lights from " + file, error);
        }
    }

    private static Vector3 lightVector(Object value) {
        require(value instanceof List<?> items && items.size() == 3, "Light vector needs three numbers");
        var items = (List<?>) value;
        return new Vector3(lightNumber(items.get(0)), lightNumber(items.get(1)), lightNumber(items.get(2)));
    }

    private static float lightNumber(Object value) {
        require(value instanceof Number number && Float.isFinite(number.floatValue()), "Light value must be finite");
        return ((Number) value).floatValue();
    }

    /**
     * A material's finish: roughness, metallic and subsurface scattering factors over its optional finish map (an image
     * key, {@link GpuMaterialMaps.Layout#METALLIC_ROUGHNESS}), its emission map's tint and its constant glow (both
     * linear), its heightmap parallax, or null, and the name of the board material whose .tres gave it, or null.
     */
    record Surface(float roughness, float metallic, float scatter, String map, float[] tint, float[] glow, Relief relief,
          String board) {
        /** glTF's own metallic-roughness: no scattering, emission as glTF's factor and texture give it, no relief. */
        Surface(float roughness, float metallic, String map) {
            this(roughness, metallic, 0, map, new float[] { 1, 1, 1 }, new float[3], null, null);
        }
    }

    /**
     * Godot's heightmap parallax: the heightmap (an image key, {@link GpuMaterialMaps.Layout#HEIGHT}), the depth along U
     * and along V in UV units (Godot's heightmap_scale / 100, negative where Godot flips the tangent or binormal), and
     * the deep parallax's layer range, 0 to 0 for a single step.
     */
    record Relief(String map, float depthU, float depthV, int minimumLayers, int maximumLayers) { }

    /** When a material's emission shows: {@code mmEmission} in its extras, else in its .tres's metadata, else always. */
    enum Emission {
        ALWAYS, NIGHT, OFF;

        static Emission of(Object mode) {
            return switch (String.valueOf(mode)) {
                case "always" -> ALWAYS;
                case "night" -> NIGHT;
                case "off" -> OFF;
                default -> throw new IllegalArgumentException("mmEmission must be always, night or off, not " + mode);
            };
        }
    }

    static final class Data extends ModelData {
        final Map<String, Image> images = new HashMap<>();
        final Map<String, Float> alphaTests = new HashMap<>();
        final Map<String, Surface> surfaces = new HashMap<>();
        /**
         * Materials whose albedo alpha is a paint mask ({@link GpuModelMaterial#paintMask}): their .tres's
         * {@code metadata/mmPaintMask}, or extras {@code mmPaintMask} on an image of the GLB's own.
         */
        final Set<String> paintMasks = new HashSet<>();
        /** Explicit building paint regions (material extras {@code mmBuildingPaint}); absent means keep the finish. */
        final Set<String> buildingPaint = new HashSet<>();
        /** When each material's emission shows; a material not listed always glows. */
        final Map<String, Emission> emissions = new HashMap<>();
        /** Emissive factors as the materials' finishes draw them, which a generated kit applies to its own finishes. */
        final Map<String, float[]> ownGlows = new HashMap<>();
        /** Materials that draw a label's characters (extras {@code mmLabel}, {@link #label}). */
        final Set<String> labels = new HashSet<>();
        /**
         * Materials textured along the board's axes, the value units a repeat ({@link GeneratedMesh#kit},
         * {@link GpuModelMaterial#boardUV}): a turned or repeated piece's floors run on across its neighbours.
         */
        final Map<String, Float> boardUVs = new HashMap<>();
        /**
         * Materials drawn alpha blended, without depth writes, by their Godot blend mode ({@link GodotMaterial#blendMode}):
         * board materials whose .tres is transparent ({@link GodotMaterial#blended}), including imported GLBs using
         * those authored materials. Without a board material the GLB's own alpha mode decides.
         */
        final Map<String, Integer> blends = new HashMap<>();
        /** Each vertex's colour slot (0 none, n the n-th of {@link #colourSlots}), in the geometry's vertex order. */
        byte[] slots = new byte[0];
        List<ColourSlot> colourSlots = List.of();

        /** Shared material metadata survives geometry assembly, clipping and colour-slot copies. */
        void inheritMaterials(Data source) {
            images.putAll(source.images);
            alphaTests.putAll(source.alphaTests);
            surfaces.putAll(source.surfaces);
            paintMasks.addAll(source.paintMasks);
            buildingPaint.addAll(source.buildingPaint);
            emissions.putAll(source.emissions);
            labels.addAll(source.labels);
            boardUVs.putAll(source.boardUVs);
            blends.putAll(source.blends);
        }
    }

    /**
     * One of a model's colour slots (the file's extras {@code mmColourSlots}): its name, its default sRGB colour and
     * named preset colours. Vertices mark their slot with the {@code _COLOUR_SLOT} attribute.
     */
    record ColourSlot(String name, String colour, List<Preset> presets) {
        record Preset(String label, String colour) { }
    }

    /**
     * The GLB that holds {@code asset} and whether to read its winter form: the asset's own file, or for a tree's winter
     * form without one ({@code <species>-snow}), the bare species' file and its materials' {@code mmSnow}.
     */
    record Source(File file, boolean snow) { }

    static Source source(File root, String asset) {
        if (asset.startsWith("buildings/")) { return new Source(megamek.common.board.TilesetScenery.customBuildingFile(asset), false); }
        File file = megamek.common.board.BoardModelBounds.file(root, asset);
        return new Source(file, !file.equals(new File(root, asset + ".glb")));
    }

    static List<ModelData> loadLods(Source source, Path textureRoot) {
        var asset = read(new FileHandle(source.file()), textureRoot);
        return loadLods(new FileHandle(source.file()), asset, source.snow());
    }

    /** A model's colour slots, from its file's JSON alone. */
    static List<ColourSlot> colourSlots(File file) {
        try {
            return colourSlots(document(file).get("extras"));
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException("Cannot read the colour slots of " + file + ": " + error.getMessage(), error);
        }
    }

    /**
     * How many mesh levels a file holds, from its JSON alone: its highest {@code <component>-lodN} group plus one (a
     * missing level between reuses the one before, {@link MeshLod#load}).
     */
    static int levels(File file) {
        try {
            int highest = 0;
            for (String name : rootNames(document(file))) {
                if (name.matches(".*-lod[0-" + (LEVELS - 1) + "]")) {
                    highest = Math.max(highest, name.charAt(name.length() - 1) - '0');
                }
            }
            return highest + 1;
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException("Cannot read the levels of " + file + ": " + error.getMessage(), error);
        }
    }

    /** The names of a GLB's root nodes in its default scene, from its JSON chunk ({@link #document}). */
    private static List<String> rootNames(Map<?, ?> document) {
        var scenes = (List<?>) document.get("scenes");
        Object index = document.get("scene");
        var roots = (List<?>) ((Map<?, ?>) scenes.get(index instanceof Number number ? number.intValue() : 0)).get("nodes");
        var nodes = (List<?>) document.get("nodes");
        List<String> result = new ArrayList<>();
        for (Object root : roots) {
            if (((Map<?, ?>) nodes.get(((Number) root).intValue())).get("name") instanceof String name) { result.add(name); }
        }
        return result;
    }

    /** The root groups a model may hold beside its levels, which the board reads as data ({@link BoardGroundCut}). */
    static final String CUT = "cut", STANDS = "stands", SPORES = "spores";

    /** Authored empty nodes inside fungal bowls; their scale is the model-space plume radius. No textures are loaded. */
    static List<Matrix4> sporeVents(File file) {
        try (var input = Files.newInputStream(file.toPath())) {
            var binary = (GltfAssetV2) new GltfAssetReader().readWithoutReferences(input);
            binary.getImageReferences().forEach(image -> image.getTarget().accept(ByteBuffer.allocate(0)));
            var model = GltfModelCreatorV2.create(binary);
            var frames = new java.util.TreeMap<String, Matrix4>();
            for (var root : model.getSceneModels().get(binary.getGltf().getScene()).getNodeModels()) {
                if (!SPORES.equals(root.getName())) { continue; }
                require(root.getMeshModels().isEmpty(), "Spore anchors must be empty nodes");
                for (var anchor : root.getChildren()) {
                    require(anchor.getMeshModels().isEmpty() && anchor.getChildren().isEmpty(), "Spore anchors must be leaf empties");
                    require(anchor.getName() != null && !frames.containsKey(anchor.getName()), "Spore anchors need unique names");
                    frames.put(anchor.getName(), new Matrix4(Z_UP).mul(new Matrix4(anchor.computeGlobalTransform(null))).mul(Y_UP));
                }
            }
            return List.copyOf(frames.values());
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException("Cannot read spore anchors from " + file + ": " + error.getMessage(), error);
        }
    }

    /**
     * A model's {@link #CUT} triangles and the numbered part each belongs to ({@link #part}, -1 for none), the frames of
     * the empties in its {@link #STANDS} group by name, and the convex outline of its finest level seen from above
     * (x, y pairs), in the board's Z-up model units; empty where it has none.
     */
    record Groundwork(List<Vector3[]> cut, int[] cutParts, Map<String, Matrix4> stands, float[] outline) {
        static final Groundwork NONE = new Groundwork(List.of(), new int[0], Map.of(), new float[0]);
    }

    /**
     * The number of a model's numbered part named {@code part-N}, as one of a platoon's fighting holes: a node of each
     * level and a child of its {@link #CUT} group; -1 for any other name.
     */
    static int part(String name) {
        return name != null && name.matches("part-\\d+") ? Integer.parseInt(name.substring("part-".length())) : -1;
    }

    /** A file's {@link Groundwork}, read without its images; a file without those groups is never read beyond its JSON. */
    static Groundwork groundwork(File file) {
        return groundwork(file, false);
    }

    /** Also reads the footprint when requested for scenery, such as rubble, without a cut or stands. */
    static Groundwork groundwork(File file, boolean footprint) {
        try {
            var names = rootNames(document(file));
            if (!footprint && !names.contains(CUT) && !names.contains(STANDS)) { return Groundwork.NONE; }
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException("Cannot read the ground work of " + file + ": " + error.getMessage(), error);
        }
        try (var input = Files.newInputStream(file.toPath())) {
            var binary = (GltfAssetV2) new GltfAssetReader().readWithoutReferences(input);
            binary.getImageReferences().forEach(image -> image.getTarget().accept(ByteBuffer.allocate(0)));
            var model = GltfModelCreatorV2.create(binary);
            List<Vector3[]> cut = new ArrayList<>(), finest = new ArrayList<>();
            List<Integer> cutParts = new ArrayList<>();
            List<NodeModel> stands = new ArrayList<>();
            for (var root : model.getSceneModels().get(binary.getGltf().getScene()).getNodeModels()) {
                if (CUT.equals(root.getName())) {
                    for (var child : root.getChildren()) {
                        triangles(child, cut);
                        while (cutParts.size() < cut.size()) { cutParts.add(part(child.getName())); }
                    }
                }
                if (STANDS.equals(root.getName())) { stands.addAll(root.getChildren()); }
                if (root.getName() != null && root.getName().endsWith("-lod0")) { triangles(root, finest); }
            }
            FloatArray points = new FloatArray();
            finest.forEach(triangle -> { for (Vector3 point : triangle) { points.add(point.x, point.y); } });
            // The hull closes on its first point.
            var hull = points.size < 6 ? new FloatArray(2) : new com.badlogic.gdx.math.ConvexHull().computePolygon(points, false);
            Map<String, Matrix4> frames = new java.util.TreeMap<>();
            for (var stand : stands) {
                frames.put(stand.getName(), new Matrix4(Z_UP).mul(new Matrix4(stand.computeGlobalTransform(null))).mul(Y_UP));
            }
            return new Groundwork(List.copyOf(cut), cutParts.stream().mapToInt(Integer::intValue).toArray(),
                  java.util.Collections.unmodifiableMap(frames),
                  java.util.Arrays.copyOf(hull.items, Math.max(0, hull.size - 2)));
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException("Cannot read the ground work of " + file + ": " + error.getMessage(), error);
        }
    }

    /** Opaque plant geometry across the three mesh LODs, without loading textures or the billboard level. */
    static List<Vector3[]> plantTriangles(File file) {
        try (var input = Files.newInputStream(file.toPath())) {
            var binary = (GltfAssetV2) new GltfAssetReader().readWithoutReferences(input);
            binary.getImageReferences().forEach(image -> image.getTarget().accept(ByteBuffer.allocate(0)));
            var model = GltfModelCreatorV2.create(binary);
            List<Vector3[]> result = new ArrayList<>();
            for (var root : model.getSceneModels().get(binary.getGltf().getScene()).getNodeModels()) {
                if (root.getName() != null && root.getName().matches(".*-lod[012]")) { triangles(root, result); }
            }
            require(!result.isEmpty(), "Expected plant mesh levels in " + file);
            return result;
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException("Cannot read plant geometry " + file + ": " + error.getMessage(), error);
        }
    }

    /** Every triangle of a node and its children under their global transforms, glTF's Y up turned to Z up. */
    private static void triangles(NodeModel node, List<Vector3[]> result) {
        Matrix4 transform = new Matrix4(Z_UP).mul(new Matrix4(node.computeGlobalTransform(null)));
        for (var mesh : node.getMeshModels()) {
            for (var primitive : mesh.getMeshPrimitiveModels()) {
                var positions = primitive.getAttributes().get("POSITION");
                float[] p = attribute(positions, positions.getCount(), 3);
                var index = primitive.getIndices();
                AccessorData indices = index == null ? null : index.getAccessorData();
                int count = index == null ? positions.getCount() : index.getCount();
                require(primitive.getMode() == GL20.GL_TRIANGLES && count % 3 == 0, "Expected triangles");
                Vector3[] triangle = new Vector3[3];
                for (int i = 0; i < count; i++) {
                    int vertex = indices == null ? i : (int) component(indices, i, 0);
                    triangle[i % 3] = new Vector3(p[vertex * 3], p[vertex * 3 + 1], p[vertex * 3 + 2]).mul(transform);
                    if (i % 3 == 2) { result.add(triangle.clone()); }
                }
            }
        }
        for (var child : node.getChildren()) { triangles(child, result); }
    }

    /** A GLB file's JSON chunk, without its buffers. */
    private static Map<?, ?> document(File file) throws IOException {
        try (var input = java.nio.file.Files.newInputStream(file.toPath())) {
            byte[] header = input.readNBytes(20);
            int length = ByteBuffer.wrap(header, 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
            return new ObjectMapper().readValue(input.readNBytes(length), Map.class);
        }
    }

    private static List<ColourSlot> colourSlots(Object extras) {
        Object slots = extras instanceof Map<?, ?> values ? values.get("mmColourSlots") : null;
        if (slots == null) { return List.of(); }
        List<ColourSlot> result = new ArrayList<>();
        for (Object slot : (List<?>) slots) {
            var entry = (Map<?, ?>) slot;
            List<ColourSlot.Preset> presets = new ArrayList<>();
            for (Object preset : (List<?>) entry.get("presets")) {
                var pair = (List<?>) preset;
                presets.add(new ColourSlot.Preset((String) pair.get(0), colour(pair.get(1))));
            }
            result.add(new ColourSlot((String) entry.get("name"), colour(entry.get("colour")), List.copyOf(presets)));
        }
        require(result.size() <= BoardDecoration.Colours.SLOTS, "At most " + BoardDecoration.Colours.SLOTS + " colour slots");
        return List.copyOf(result);
    }

    private static String colour(Object value) {
        // The same rule as a placed object's colours.
        return BoardDecoration.Colours.of((String) value).slot(0);
    }

    /**
     * Replace the colours of {@code data}'s slot vertices with {@code colours} (a layout row's, a truss deck's or an
     * editor preview's). Each channel keeps its ratio to the slot's default colour in linear light, so the shaded parts
     * of a slot stay shaded.
     * <p>
     * KEEP IN SYNC with model-colour-slots.glsl#slotColour, its GPU twin, which recolours a placed object's one shared
     * mesh ({@link #colourSlotMesh}) in every view. Both read each slot's replacement from the same {@link #packed}
     * 0xRRGGBB (channel / 255), skip a slot whose value is negative, and apply {@code shade x linear(replacement)}
     * with exact sRGB input decoding and no clamping; {@link #shade} is their shared factor.
     * RigidGlbTest#theSharedMeshAndPackedColoursGiveTheRecolouredCopysVertexColours compares the two per vertex.
     */
    static void recolour(ModelData data, BoardDecoration.Colours colours) {
        var model = (Data) data;
        float[] packed = packed(colours);
        if (model.slots.length == 0 || packed == null) { return; }
        float[] vertices = model.meshes.first().vertices;
        for (int vertex = 0; vertex < model.slots.length; vertex++) {
            int slot = model.slots[vertex] - 1;
            if (slot < 0 || slot >= model.colourSlots.size() || packed[slot] < 0) { continue; }
            Color from = Color.valueOf(model.colourSlots.get(slot).colour());
            int rgb = (int) packed[slot];
            float[] defaults = { from.r, from.g, from.b };
            float[] replacements = { (rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f };
            for (int channel = 0; channel < 3; channel++) {
                int at = vertex * STRIDE + 6 + channel;
                vertices[at] = shade(vertices[at], defaults[channel]) * GpuColor.linear(replacements[channel]);
            }
        }
    }

    /** A label atlas's characters, in its cells' order: 8 by 8 from the top left, the first one blank. */
    static final String LABEL_CHARACTERS = " ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-";
    private static final int LABEL_CELLS = 8;

    /**
     * Writes {@code text} on {@code data}'s label materials ({@link Data#labels}): each of their quads is one character
     * place, its index the integer part of its U, and maps its atlas cell's fraction (U and V within the first, blank
     * cell). The text is centred over the places; characters outside {@link #LABEL_CHARACTERS} are blank.
     */
    static void label(ModelData data, String text) {
        var model = (Data) data;
        if (model.labels.isEmpty()) { return; }
        Set<String> parts = new HashSet<>();
        for (var node : model.nodes) { collectLabelParts(node, model.labels, parts); }
        float[] vertices = model.meshes.first().vertices;
        Set<Integer> labelled = new java.util.TreeSet<>();
        for (var part : model.meshes.first().parts) {
            if (!parts.contains(part.id)) { continue; }
            for (short index : part.indices) { labelled.add(Short.toUnsignedInt(index)); }
        }
        int places = 0;
        for (int vertex : labelled) { places = Math.max(places, (int) Math.floor(vertices[vertex * STRIDE + 10]) + 1); }
        String shown = text.toUpperCase(java.util.Locale.ROOT);
        shown = shown.substring(0, Math.min(shown.length(), places));
        int start = (places - shown.length()) / 2;
        for (int vertex : labelled) {
            int at = vertex * STRIDE + 10;
            int place = (int) Math.floor(vertices[at]);
            float u = (vertices[at] - place) * LABEL_CELLS, v = vertices[at + 1] * LABEL_CELLS;
            int character = place - start >= 0 && place - start < shown.length()
                  ? Math.max(0, LABEL_CHARACTERS.indexOf(shown.charAt(place - start))) : 0;
            vertices[at] = place + (character % LABEL_CELLS + u) / LABEL_CELLS;
            vertices[at + 1] = (character / LABEL_CELLS + v) / LABEL_CELLS;
        }
    }

    private static void collectLabelParts(ModelNode node, Set<String> materials, Set<String> parts) {
        for (var part : node.parts) {
            if (materials.contains(part.materialId)) { parts.add(part.meshPartId); }
        }
        if (node.children != null) {
            for (var child : node.children) { collectLabelParts(child, materials, parts); }
        }
    }

    /**
     * A slot vertex's colour channel relative to its slot's default, in linear light; 1 where the default is black, so
     * that vertex takes the replacement itself. The one factor of {@link #recolour} and of {@link #colourSlotMesh}.
     */
    private static float shade(float vertex, float slotDefault) {
        float d = GpuColor.linear(slotDefault);
        return d > 1e-6f ? vertex / d : 1;
    }

    /**
     * A placed object's one mesh for every colouring of a recolourable model, in every view: {@code data} with one more vertex attribute,
     * {@link #COLOUR_SLOT}, holding each vertex's shade per channel ({@link #shade}) and its slot (w, 0 for none). The
     * vertex shader applies a placement's colours ({@link #packed}) by {@link #recolour}'s rule
     * (model-colour-slots.glsl). A copy: materials, nodes, parts and images are shared with {@code data}.
     */
    static ModelData colourSlotMesh(ModelData data) {
        var source = (Data) data;
        var result = new Data();
        result.id = source.id;
        result.materials.addAll(source.materials);
        result.nodes.addAll(source.nodes);
        result.animations.addAll(source.animations);
        result.inheritMaterials(source);
        result.slots = source.slots;
        result.colourSlots = source.colourSlots;
        ModelMesh mesh = source.meshes.first(), slotted = new ModelMesh();
        slotted.id = mesh.id;
        slotted.parts = mesh.parts;
        slotted.attributes = java.util.Arrays.copyOf(mesh.attributes, mesh.attributes.length + 1);
        slotted.attributes[mesh.attributes.length] = new VertexAttribute(
              com.badlogic.gdx.graphics.VertexAttributes.Usage.Generic, 4, COLOUR_SLOT);
        int count = mesh.vertices.length / STRIDE, stride = STRIDE + 4;
        slotted.vertices = new float[count * stride];
        for (int vertex = 0; vertex < count; vertex++) {
            System.arraycopy(mesh.vertices, vertex * STRIDE, slotted.vertices, vertex * stride, STRIDE);
            int at = vertex * stride + STRIDE;
            int slot = vertex < source.slots.length ? source.slots[vertex] - 1 : -1;
            if (slot < 0 || slot >= source.colourSlots.size()) {
                slotted.vertices[at] = slotted.vertices[at + 1] = slotted.vertices[at + 2] = 1;
                continue;
            }
            Color from = Color.valueOf(source.colourSlots.get(slot).colour());
            float[] defaults = { from.r, from.g, from.b };
            for (int channel = 0; channel < 3; channel++) {
                slotted.vertices[at + channel] = shade(mesh.vertices[vertex * STRIDE + 6 + channel], defaults[channel]);
            }
            slotted.vertices[at + 3] = slot + 1;
        }
        result.meshes.add(slotted);
        return result;
    }

    /** Whether a mesh is a recolourable model's shared mesh ({@link #colourSlotMesh}). */
    static boolean colourSlotted(com.badlogic.gdx.graphics.VertexAttributes attributes) {
        for (VertexAttribute attribute : attributes) {
            if (attribute.alias.equals(COLOUR_SLOT)) { return true; }
        }
        return false;
    }

    /**
     * A placement's colours for the shared mesh: each slot's 0xRRGGBB as a float (exact below 2^24), or -1 where the
     * slot keeps the model's own colour; null for an object in its model's own colours.
     */
    static float[] packed(BoardDecoration.Colours colours) {
        if (colours.slots().isEmpty()) { return null; }
        float[] result = new float[OWN_COLOURS.length];
        for (int slot = 0; slot < result.length; slot++) {
            String colour = colours.slot(slot);
            result[slot] = colour == null ? -1 : Integer.parseInt(colour.substring(1), 16);
        }
        return result;
    }

    /** The colours a renderable's placement passes to the shared mesh ({@link #packed}, its userData), or its own. */
    static float[] slotColours(Object userData) {
        return userData instanceof float[] colours ? colours : OWN_COLOURS;
    }

    static ModelData load(FileHandle file) {
        return load(file, file.file().toPath().toAbsolutePath().getParent());
    }

    static ModelData load(FileHandle file, Path textureRoot) {
        var asset = read(file, textureRoot);
        var source = asset.model();
        return convert(file, file.nameWithoutExtension(), asset, source.getSceneModels().get(asset.scene()).getNodeModels(), false);
    }

    /** Levels a file may hold: the near mesh, two simpler meshes and a plant's impostor cards. */
    static final int LEVELS = 4;

    /** One file per component, identity groups named <component>-lodN. Missing levels reuse the preceding data. */
    static List<ModelData> loadLods(FileHandle file) {
        return loadLods(file, file.file().toPath().toAbsolutePath().getParent());
    }

    static List<ModelData> loadLods(FileHandle file, Path textureRoot) {
        return loadLods(file, read(file, textureRoot), false);
    }

    private static List<ModelData> loadLods(FileHandle file, Asset asset, boolean snow) {
        var source = asset.model();
        var roots = source.getSceneModels().get(asset.scene()).getNodeModels();
        String shape = file.nameWithoutExtension();
        Map<Integer, NodeModel> groups = new HashMap<>();
        if (source.getNodeModels().stream().anyMatch(root -> megamek.common.board.BuildingVolumes.helper(root.getName()))
              && roots.stream().noneMatch(root -> MeshLod.level(root.getName()) >= 0)) {
            return java.util.Collections.nCopies(LEVELS, convert(file, shape + "-lod0", asset, roots, snow));
        }
        // Every level is named, so which mesh draws at a given size is always visible in the file itself: a group's name
        // ends with its level, whatever precedes it, so a renamed or moved file keeps its levels.
        for (var group : roots) {
            if (CUT.equals(group.getName()) || STANDS.equals(group.getName()) || SPORES.equals(group.getName())
                  || megamek.common.board.BuildingVolumes.helper(group.getName())) { continue; }
            int level = MeshLod.level(group.getName());
            require(level >= 0 && level < LEVELS, "Unexpected LOD group in " + file.name() + ": " + group.getName()
                  + " (name the levels' groups ...-lod0, and optionally ...-lod1 to ...-lod" + (LEVELS - 1) + ")");
            require(groups.put(level, group) == null, "Duplicate LOD group: " + group.getName());
            require(group.getMeshModels().isEmpty() && !group.getChildren().isEmpty(), "LOD groups contain child nodes");
            require(java.util.Arrays.equals(new Matrix4().val, group.computeLocalTransform(null)),
                  "LOD group transforms must be identity; transform the child rig instead");
        }
        return MeshLod.load(shape, LEVELS, name -> {
            var group = groups.get(MeshLod.level(name));
            return group == null ? null : convert(file, group.getName(), asset, group.getChildren(), snow);
        });
    }

    private record Asset(GltfModel model, int scene, Map<TextureModel, Image> images, Path textureRoot,
          List<ColourSlot> colourSlots) { }

    private static Asset read(FileHandle file, Path textureRoot) {
        try (var input = file.read()) {
            var reader = new GltfAssetReader();
            reader.setJsonErrorConsumer(error -> { throw new IllegalArgumentException(error.toString()); });
            var asset = reader.readWithoutReferences(input);
            require(asset instanceof GltfAssetV2, "Expected glTF 2.0");
            var binary = (GltfAssetV2) asset;
            var document = binary.getGltf();
            // Empty squad references contain a rigid hierarchy and no geometry buffer.
            require(document.getBuffers() == null || (binary.getBinaryData() != null
                  && document.getBuffers().stream().allMatch(buffer -> buffer.getUri() == null)),
                  "Expected embedded geometry buffers");
            require(document.getExtensionsRequired() == null || document.getExtensionsRequired().isEmpty(),
                  "Required glTF extensions are unsupported");
            require(document.getSkins() == null && document.getAnimations() == null,
                  "Rigid kits use runtime animation");
            require(document.getScene() != null, "GLB needs a default scene");
            // Resolve local images explicitly; the glTF reader never fetches a URI from the network.
            for (var reference : binary.getImageReferences()) {
                var path = imagePath(file, textureRoot, reference.getUri());
                reference.getTarget().accept(ByteBuffer.wrap(Files.readAllBytes(path)));
            }
            var model = GltfModelCreatorV2.create(binary);
            Map<TextureModel, Image> images = new IdentityHashMap<>();
            for (var texture : model.getTextureModels()) {
                var image = texture.getImageModel();
                require(Set.of("image/png", "image/jpeg").contains(image.getMimeType()), "Expected a PNG or JPEG image");
                String path = image.getUri() == null ? null : imagePath(file, textureRoot, image.getUri()).toString();
                byte[] encoded = null;
                String key = path;
                if (path == null) {
                    var bytes = image.getImageData().duplicate();
                    encoded = new byte[bytes.remaining()];
                    bytes.get(encoded);
                    key = file.file().toPath().toAbsolutePath().normalize() + "#image-" + model.getImageModels().indexOf(image);
                }
                Integer samplerIndex = document.getTextures().get(model.getTextureModels().indexOf(texture)).getSampler();
                var sampler = samplerIndex == null ? null : document.getSamplers().get(samplerIndex);
                int min = sampler == null || sampler.getMinFilter() == null ? 9987 : sampler.getMinFilter();
                int mag = sampler == null || sampler.getMagFilter() == null ? 9729 : sampler.getMagFilter();
                int wrapS = sampler == null || sampler.getWrapS() == null ? 10497 : sampler.getWrapS();
                int wrapT = sampler == null || sampler.getWrapT() == null ? 10497 : sampler.getWrapT();
                require(Set.of(9728, 9729, 9984, 9985, 9986, 9987).contains(min)
                      && Set.of(9728, 9729).contains(mag)
                      && Set.of(33071, 33648, 10497).contains(wrapS)
                      && Set.of(33071, 33648, 10497).contains(wrapT), "Unsupported texture sampler");
                if (min != 9987 || mag != 9729 || wrapS != 10497 || wrapT != 10497) {
                    key += "#sampler-" + min + "-" + mag + "-" + wrapS + "-" + wrapT;
                }
                images.put(texture, new Image(key, encoded, path, min, mag, wrapS, wrapT));
            }
            return new Asset(model, document.getScene(), images, textureRoot, colourSlots(document.getExtras()));
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException("Cannot read rigid GLB " + file + ": " + error.getMessage(), error);
        }
    }

    private static void texture(ModelMaterial target, int usage, String key) {
        var texture = new ModelTexture();
        texture.id = target.id;
        texture.usage = usage;
        texture.fileName = key;
        if (target.textures == null) { target.textures = new com.badlogic.gdx.utils.Array<>(); }
        target.textures.add(texture);
    }

    /**
     * Adds generated geometry's material {@code id} to {@code result} as the board material {@code name}: its albedo the
     * base colour, sampled as a GLB's default sampler does, and its .tres the finish and every other map, as a GLB
     * material on that albedo draws.
     */
    static void boardMaterial(Data result, String id, String name) {
        var board = GpuMaterialMaps.board(name);
        String path = board.albedo().toPath().toAbsolutePath().normalize().toString();
        var base = new Image(path, null, path, GL20.GL_LINEAR_MIPMAP_LINEAR, GL20.GL_LINEAR, GL20.GL_REPEAT, GL20.GL_REPEAT);
        result.images.put(base.key(), base);
        var target = new ModelMaterial();
        target.id = id;
        target.diffuse = new Color(Color.WHITE);
        texture(target, ModelTexture.USAGE_DIFFUSE, base.key());
        result.surfaces.put(id, boardSurface(board, base, target, result));
        if (board.blended()) { result.blends.put(id, board.blendMode()); }
        result.materials.add(target);
    }

    /**
     * Makes material {@code id} light up after dusk in {@code colour} (linear RGB: warm, cold or any other), whatever it
     * is: the colour tints its emission map, or without one it glows evenly. A colour is only a uniform of the material's
     * program, with no light to evaluate per pixel and no program of its own, so a board can hold any number of them.
     */
    static void nightEmission(Data result, String id, float... colour) {
        glow(result, id, colour);
        result.emissions.put(id, Emission.NIGHT);
    }

    /** Material {@code id}'s emission in {@code colour} (linear RGB): it tints its emission map, or without one it glows evenly. */
    static void glow(Data result, String id, float... colour) {
        boolean mapped = false;
        for (var material : result.materials) {
            if (material.id.equals(id) && material.textures != null) {
                for (var texture : material.textures) { mapped |= texture.usage == ModelTexture.USAGE_EMISSIVE; }
            }
        }
        var surface = result.surfaces.get(id);
        result.surfaces.put(id, new Surface(surface.roughness(), surface.metallic(), surface.scatter(), surface.map(),
              mapped ? colour : surface.tint(), mapped ? surface.glow() : colour, surface.relief(), surface.board()));
    }

    /** Material {@code id}'s own emission in place of its board material's, as {@link #boardSurface} leaves it: an even glow. */
    static void ownGlow(Data result, String id, float... colour) {
        for (var material : result.materials) {
            if (material.id.equals(id) && material.textures != null) {
                for (int at = material.textures.size - 1; at >= 0; at--) {
                    if (material.textures.get(at).usage == ModelTexture.USAGE_EMISSIVE) { material.textures.removeIndex(at); }
                }
            }
        }
        glow(result, id, colour);
    }

    /**
     * Adds generated geometry's material {@code id} as an opaque, clamped picture of {@code file}, its image the colour and
     * emission maps ({@link #nightEmission} sets how it lights up) over the normal and occlusion maps of the board material
     * {@code finish}. It has the layout of a building's lit windows and draws with their existing programs. It is matte
     * as printed vinyl, past the roughness where a local light leaves a highlight (lavaLighting's glossy test).
     */
    static void pictureMaterial(Data result, String id, File file, String finish) {
        boardMaterial(result, id, finish);
        String path = file.toPath().toAbsolutePath().normalize().toString();
        var image = new Image(path, null, path, GL20.GL_LINEAR_MIPMAP_LINEAR, GL20.GL_LINEAR, GL20.GL_CLAMP_TO_EDGE, GL20.GL_CLAMP_TO_EDGE);
        result.images.put(image.key(), image);
        var target = result.materials.peek();
        for (var texture : target.textures) {
            if (texture.usage == ModelTexture.USAGE_DIFFUSE) { texture.fileName = image.key(); }
        }
        texture(target, ModelTexture.USAGE_EMISSIVE, image.key());
        var surface = result.surfaces.get(id);
        result.surfaces.put(id, new Surface(.85f, 0, 0, null, surface.tint(), surface.glow(), null, surface.board()));
    }

    /**
     * A board material's finish, adding to {@code target} the normal, occlusion and emission maps of its .tres the GLB
     * leaves out, each sampled as the base colour is. Its factors and maps replace the GLB's own metallic-roughness.
     * Its metadata says when it glows, unless the GLB's material has already said so, and whether its alpha is a paint mask.
     */
    private static Surface boardSurface(GodotMaterial board, Image base, ModelMaterial target, Data result) {
        String emission = board.text("metadata/mmEmission");
        result.emissions.putIfAbsent(target.id, emission == null ? Emission.ALWAYS : Emission.of(emission));
        if (board.flag("metadata/mmPaintMask", false)) { result.paintMasks.add(target.id); }
        String sampler = base.key().substring(base.file().length());
        java.util.function.Function<File, String> image = file -> {
            String path = file.toPath().toAbsolutePath().normalize().toString();
            var copy = new Image(path + sampler, null, path, base.minFilter(), base.magFilter(), base.wrapS(), base.wrapT());
            result.images.put(copy.key(), copy);
            return copy.key();
        };
        var finish = GpuMaterialMaps.modelFinish(board);
        boolean ownGlow = target.emissive != null && target.emissive.r + target.emissive.g + target.emissive.b > 0;
        for (int usage : new int[] { ModelTexture.USAGE_NORMAL, ModelTexture.USAGE_AMBIENT, ModelTexture.USAGE_EMISSIVE }) {
            File map = switch (usage) {
                case ModelTexture.USAGE_NORMAL -> board.normal();
                case ModelTexture.USAGE_AMBIENT -> board.occlusion();
                default -> ownGlow ? null : finish.emission();
            };
            boolean present = target.textures != null && java.util.stream.StreamSupport.stream(target.textures.spliterator(),
                  false).anyMatch(texture -> texture.usage == usage);
            if (map != null && !present) { texture(target, usage, image.apply(map)); }
        }
        Relief relief = finish.heightmap() == null ? null : new Relief(image.apply(finish.heightmap()), finish.depthU(),
              finish.depthV(), finish.minimumLayers(), finish.maximumLayers());
        // An authored lamp/window may choose its own colour on a shared glass finish. Do not add the
        // library's default window glow to that explicit glTF emission (e.g. a cool stadium floodlight lens).
        return new Surface(finish.roughness(), finish.metallic(), finish.scatter(),
              finish.map() == null ? null : image.apply(finish.map()), finish.tint(), ownGlow ? new float[3] : finish.glow(), relief,
              GpuMaterialMaps.name(board, GpuMaterialMaps.boardTextures()));
    }

    private static Path imagePath(FileHandle file, Path root, String value) throws IOException {
        var uri = URI.create(value);
        require(!uri.isAbsolute() && uri.getAuthority() == null && uri.getQuery() == null
              && uri.getFragment() == null, "Textures must use local relative paths");
        return UnitModelDescriptor.contained(root, file.file().toPath().toAbsolutePath().getParent().resolve(uri.getPath()));
    }

    /**
     * {@code snow} reads each material's {@code mmSnow} extras instead of its own
     * values where they name them: {@code name}, {@code textures} by role ({@code baseColor}, {@code normal},
     * {@code occlusion}, {@code emissive}), {@code colourScale} (linear factors on its triangles' vertex colours) and {@code colours}
     * (its one primitive's linear vertex colours). The bare species' GLB carries them.
     */
    private static ModelData convert(FileHandle file, String id, Asset asset, List<NodeModel> roots, boolean snow) {
        try {
            Data result = new Data();
            var source = asset.model();
            result.id = id;
            Map<MaterialModel, String> materials = new IdentityHashMap<>();
            Map<MaterialModel, Map<?, ?>> variants = new IdentityHashMap<>();
            Set<String> materialNames = new HashSet<>();
            for (var sourceMaterial : source.getMaterialModels()) {
                var material = (MaterialModelV2) sourceMaterial;
                require(material.getAlphaMode() != MaterialModelV2.AlphaMode.BLEND,
                      "Rigid kits use opaque or alpha-tested materials");
                Map<?, ?> changes = snow && material.getExtras() instanceof Map<?, ?> extras
                      && extras.get("mmSnow") instanceof Map<?, ?> own ? own : Map.of();
                variants.put(material, changes);
                var target = new ModelMaterial();
                target.id = changes.containsKey("name") ? (String) changes.get("name") : material.getName();
                require(target.id != null && materialNames.add(target.id), "Material names must be unique");
                if (material.getExtras() instanceof Map<?, ?> extras) {
                    if (extras.get("mmEmission") != null) { result.emissions.put(target.id, Emission.of(extras.get("mmEmission"))); }
                    if (Boolean.TRUE.equals(extras.get("mmBuildingPaint"))) { result.buildingPaint.add(target.id); }
                    if (Boolean.TRUE.equals(extras.get("mmLabel"))) { result.labels.add(target.id); }
                }
                if (material.getAlphaMode() == MaterialModelV2.AlphaMode.MASK) {
                    float cutoff = material.getAlphaCutoff();
                    require(Float.isFinite(cutoff) && cutoff >= 0 && cutoff <= 1, "Invalid alpha cutoff");
                    result.alphaTests.put(target.id, cutoff);
                }
                float[] color = material.getBaseColorFactor();
                target.diffuse = new Color(color[0], color[1], color[2], color[3]);
                float[] emission = material.getEmissiveFactor();
                // glTF factors are linear. A texture still needs its default black factor, or it would glow on its own.
                if (material.getEmissiveTexture() != null || emission[0] != 0 || emission[1] != 0 || emission[2] != 0) {
                    target.emissive = new Color(emission[0], emission[1], emission[2], 1);
                }
                float roughness = material.getRoughnessFactor(), metallic = material.getMetallicFactor();
                require(Float.isFinite(roughness) && roughness >= 0 && roughness <= 1
                      && Float.isFinite(metallic) && metallic >= 0 && metallic <= 1, "Invalid metallic-roughness factors");
                String surfaceMap = null;
                if (material.getMetallicRoughnessTexture() != null) {
                    Integer coordinate = material.getMetallicRoughnessTexcoord();
                    require(coordinate == null || coordinate == 0, "Only TEXCOORD_0 is supported");
                    var image = asset.images().get(material.getMetallicRoughnessTexture());
                    result.images.put(image.key(), image);
                    surfaceMap = image.key();
                }
                Image base = null;
                for (int usage : new int[] { ModelTexture.USAGE_DIFFUSE, ModelTexture.USAGE_NORMAL, ModelTexture.USAGE_AMBIENT,
                      ModelTexture.USAGE_EMISSIVE }) {
                    var sourceTexture = switch (usage) {
                        case ModelTexture.USAGE_NORMAL -> material.getNormalTexture();
                        case ModelTexture.USAGE_AMBIENT -> material.getOcclusionTexture();
                        case ModelTexture.USAGE_EMISSIVE -> material.getEmissiveTexture();
                        default -> material.getBaseColorTexture();
                    };
                    if (sourceTexture == null) { continue; }
                    Integer coordinate = switch (usage) {
                        case ModelTexture.USAGE_NORMAL -> material.getNormalTexcoord();
                        case ModelTexture.USAGE_AMBIENT -> material.getOcclusionTexcoord();
                        case ModelTexture.USAGE_EMISSIVE -> material.getEmissiveTexcoord();
                        default -> material.getBaseColorTexcoord();
                    };
                    require(coordinate == null || coordinate == 0,
                          "Only TEXCOORD_0 is supported");
                    require(usage != ModelTexture.USAGE_NORMAL || material.getNormalScale() == 1,
                          "Bake normal strength into the map");
                    require(usage != ModelTexture.USAGE_AMBIENT || material.getOcclusionStrength() == 1,
                          "Bake occlusion strength into the map");
                    var image = asset.images().get(sourceTexture);
                    String role = switch (usage) {
                        case ModelTexture.USAGE_NORMAL -> "normal";
                        case ModelTexture.USAGE_AMBIENT -> "occlusion";
                        case ModelTexture.USAGE_EMISSIVE -> "emissive";
                        default -> "baseColor";
                    };
                    if (changes.get("textures") instanceof Map<?, ?> textures && textures.get(role) instanceof String uri) {
                        // The variant's image, sampled as the one it replaces.
                        String path = imagePath(file, asset.textureRoot(), uri).toString();
                        String sampler = image.file() == null ? "" : image.key().substring(image.file().length());
                        image = new Image(path + sampler, null, path, image.minFilter(), image.magFilter(), image.wrapS(), image.wrapT());
                    }
                    result.images.put(image.key(), image);
                    if (usage == ModelTexture.USAGE_DIFFUSE) { base = image; }
                    texture(target, usage, image.key());
                }
                // A base colour that is a board material's albedo makes the material that board material, as Godot draws
                // it: its .tres supplies the finish and every map the GLB leaves out.
                var board = base == null || base.file() == null ? null
                      : GpuMaterialMaps.boardMaterial(new File(base.file()), GpuMaterialMaps.boardTextures());
                result.surfaces.put(target.id, board == null ? new Surface(roughness, metallic, surfaceMap)
                      : boardSurface(board, base, target, result));
                if (board != null && board.blended()) { result.blends.put(target.id, board.blendMode()); }
                // A board material's own metadata says whether its alpha is a paint mask.
                if (board == null && material.getExtras() instanceof Map<?, ?> extras && Boolean.TRUE.equals(extras.get("mmPaintMask"))) {
                    result.paintMasks.add(target.id);
                }
                result.materials.add(target);
                materials.put(material, target.id);
                // Every night light's colour is a uniform (nightEmission): its glTF emissive factor as the material's finish
                // draws it. One the file switches off keeps its factor, so switching it back on restores it.
                if (target.emissive != null) {
                    float[] tint = result.surfaces.get(target.id).tint();
                    Color factor = target.emissive;
                    float[] own = { factor.r * tint[0], factor.g * tint[1], factor.b * tint[2] };
                    if (own[0] + own[1] + own[2] > 0) { result.ownGlows.put(target.id, own); }
                    if (result.emissions.get(target.id) == Emission.NIGHT) {
                        target.emissive = null;
                        nightEmission(result, target.id, own);
                    }
                }
            }
            Map<MeshPrimitiveModel, String> partIds = new IdentityHashMap<>();
            Map<Map<String, AccessorModel>, Integer> offsets = new HashMap<>();
            Map<Integer, Integer> backVertices = new HashMap<>();
            Set<String> names = new HashSet<>();
            List<ModelMeshPart> parts = new ArrayList<>();
            FloatArray vertices = new FloatArray();
            ByteArray slots = new ByteArray();
            Set<Integer> painted = new HashSet<>();
            Set<MeshModel> meshes = new java.util.LinkedHashSet<>();
            Set<NodeModel> collected = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (var root : roots) { collectMeshes(root, meshes, collected); }
            for (var mesh : meshes) {
                for (var primitive : mesh.getMeshPrimitiveModels()) {
                    require(primitive.getMode() == GL20.GL_TRIANGLES && primitive.getTargets().isEmpty(),
                          "Expected rigid triangle primitives");
                    var attributes = primitive.getAttributes();
                    int base = offsets.computeIfAbsent(attributes, key -> append(vertices, slots, key));
                    var positions = attributes.get("POSITION");
                    var index = primitive.getIndices();
                    int count = index == null ? positions.getCount() : index.getCount();
                    require(count > 0 && count % 3 == 0, "Incomplete triangle");
                    AccessorData indices = index == null ? null : index.getAccessorData();
                    require(index == null || !index.isNormalized() && indices.getNumComponentsPerElement() == 1
                          && Set.of(5121, 5123, 5125).contains(index.getComponentType()), "Invalid index accessor");
                    ModelMeshPart part = new ModelMeshPart();
                    Object authored = primitive.getExtras() instanceof Map<?, ?> extras ? extras.get("mmPart") : null;
                    part.id = authored instanceof String partId ? partId : "part-" + parts.size();
                    require(names.add(part.id), "Duplicate mesh part: " + part.id);
                    part.primitiveType = GL20.GL_TRIANGLES;
                    part.indices = new short[count];
                    for (int i = 0; i < count; i++) {
                        long value = index == null ? i : (long) component(indices, i, 0);
                        require(value >= 0 && value < positions.getCount(), "Vertex index outside primitive");
                        part.indices[i] = (short) (value + base);
                    }
                    paint(vertices, part.indices, base, positions.getCount(), variants.getOrDefault(primitive.getMaterialModel(), Map.of()),
                          painted);
                    if (primitive.getMaterialModel() instanceof MaterialModelV2 material && material.isDoubleSided()) {
                        part.indices = doubleSided(vertices, slots, part.indices, backVertices);
                    }
                    parts.add(part);
                    partIds.put(primitive, part.id);
                }
            }
            if (!parts.isEmpty()) {
                var mesh = new ModelMesh();
                mesh.id = "geometry";
                mesh.attributes = new VertexAttribute[] { VertexAttribute.Position(), VertexAttribute.Normal(),
                      VertexAttribute.ColorUnpacked(), VertexAttribute.TexCoords(0) };
                mesh.vertices = vertices.toArray();
                mesh.parts = parts.toArray(ModelMeshPart[]::new);
                result.meshes.add(mesh);
                result.slots = slots.toArray();
                result.colourSlots = asset.colourSlots();
            }
            Set<NodeModel> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            Set<String> nodeNames = new HashSet<>();
            for (var node : roots) {
                if (megamek.common.board.BuildingVolumes.helper(node.getName())) { continue; }
                result.nodes.add(node(node, partIds, materials, visited, nodeNames));
            }
            return result;
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException("Cannot read rigid GLB " + file + ": " + error.getMessage(), error);
        }
    }

    /**
     * A material's winter vertex colours on one primitive, before its reverse faces copy them: {@code colourScale} on
     * the vertices its triangles use (each once), or {@code colours} for all of its vertices.
     */
    private static void paint(FloatArray vertices, short[] indices, int base, int count, Map<?, ?> changes, Set<Integer> painted) {
        if (changes.get("colourScale") instanceof List<?> scale) {
            for (short index : indices) {
                int vertex = Short.toUnsignedInt(index);
                if (!painted.add(vertex)) { continue; }
                for (int channel = 0; channel < 3; channel++) {
                    int at = vertex * STRIDE + 6 + channel;
                    vertices.items[at] *= ((Number) scale.get(channel)).floatValue();
                }
            }
        }
        if (changes.get("colours") instanceof List<?> colours) {
            require(colours.size() == count, "mmSnow's colours need one value per vertex");
            for (int vertex = 0; vertex < count; vertex++) {
                var value = (List<?>) colours.get(vertex);
                int at = (base + vertex) * STRIDE + 6;
                for (int channel = 0; channel < 4; channel++) {
                    float component = ((Number) value.get(channel)).floatValue();
                    vertices.items[at + channel] = component;
                }
            }
        }
    }

    /**
     * Bake the reverse face once at import: vertex lighting needs reversed normals, not merely disabled culling.
     * All existing colour, shadow, instancing and cutaway paths then use the same ordinary single-sided geometry.
     * Shared front vertices also share their reverse vertices, including across material primitives.
     */
    private static short[] doubleSided(FloatArray vertices, ByteArray slots, short[] front, Map<Integer, Integer> backVertices) {
        short[] indices = java.util.Arrays.copyOf(front, Math.multiplyExact(front.length, 2));
        for (int triangle = 0; triangle < front.length; triangle += 3) {
            for (int corner = 0; corner < 3; corner++) {
                int original = Short.toUnsignedInt(front[triangle + 2 - corner]);
                int back = backVertices.computeIfAbsent(original, key -> {
                    int index = vertices.size / STRIDE;
                    require(index < 65535, "Rigid asset exceeds the vertex budget including double-sided faces");
                    // Reserve before copying from the same array: growing it must not invalidate the source.
                    vertices.ensureCapacity(STRIDE);
                    vertices.addAll(vertices.items, key * STRIDE, STRIDE);
                    slots.add(slots.get(key));
                    for (int axis = 3; axis < 6; axis++) { vertices.items[index * STRIDE + axis] *= -1; }
                    return index;
                });
                indices[front.length + triangle + corner] = (short) back;
            }
        }
        return indices;
    }

    private static void collectMeshes(NodeModel node, Set<MeshModel> meshes, Set<NodeModel> visited) {
        if (megamek.common.board.BuildingVolumes.helper(node.getName())) { return; }
        require(visited.add(node), "Node hierarchy contains a cycle or repeated child");
        meshes.addAll(node.getMeshModels());
        for (var child : node.getChildren()) { collectMeshes(child, meshes, visited); }
    }

    private static int append(FloatArray vertices, ByteArray slots, Map<String, AccessorModel> attributes) {
        require(attributes.keySet().stream().allMatch(Set.of("POSITION", "NORMAL", "COLOR_0", "TEXCOORD_0", "_COLOUR_SLOT")::contains),
              "Unsupported rigid vertex attribute");
        var positions = attributes.get("POSITION");
        require(positions != null && attributes.containsKey("NORMAL"), "Positions and normals are required");
        int count = positions.getCount(), base = vertices.size / STRIDE;
        require(count > 0 && count + base <= 65535, "Rigid asset exceeds the vertex budget");
        float[] p = attribute(positions, count, 3);
        float[] n = attribute(attributes.get("NORMAL"), count, 3);
        var colors = attributes.get("COLOR_0");
        int channels = colors == null ? 4 : colors.getElementType().getNumComponents();
        require(channels == 3 || channels == 4, "Expected RGB or RGBA colors");
        float[] c = colors == null ? null : attribute(colors, count, channels);
        float[] uv = attributes.containsKey("TEXCOORD_0") ? attribute(attributes.get("TEXCOORD_0"), count, 2) : null;
        float[] slot = attributes.containsKey("_COLOUR_SLOT") ? attribute(attributes.get("_COLOUR_SLOT"), count, 1) : null;
        for (int i = 0; i < count; i++) {
            require(slot == null || slot[i] >= 0 && slot[i] <= BoardDecoration.Colours.SLOTS, "Invalid colour slot");
            slots.add((byte) (slot == null ? 0 : Math.round(slot[i])));
            vertices.addAll(p[i * 3], -p[i * 3 + 2], p[i * 3 + 1]);
            vertices.addAll(n[i * 3], -n[i * 3 + 2], n[i * 3 + 1]);
            vertices.addAll(c == null ? 1 : c[i * channels], c == null ? 1 : c[i * channels + 1],
                  c == null ? 1 : c[i * channels + 2], c == null || channels == 3 ? 1 : c[i * channels + 3]);
            vertices.addAll(uv == null ? 0 : uv[i * 2], uv == null ? 0 : uv[i * 2 + 1]);
        }
        return base;
    }

    private static float[] attribute(AccessorModel accessor, int count, int size) {
        var data = accessor.getAccessorData();
        require(accessor.getCount() == count && data.getNumComponentsPerElement() == size, "Mismatched attributes");
        float[] result = new float[count * size];
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < size; j++) {
                float value = component(data, i, j);
                if (accessor.isNormalized()) {
                    value = switch (accessor.getComponentType()) {
                        case 5120 -> Math.max(value / 127, -1);
                        case 5121 -> value / 255;
                        case 5122 -> Math.max(value / 32767, -1);
                        case 5123 -> value / 65535;
                        default -> throw new IllegalArgumentException("Invalid normalized accessor");
                    };
                }
                require(Float.isFinite(value), "Non-finite vertex");
                result[i * size + j] = value;
            }
        }
        return result;
    }

    private static float component(AccessorData data, int index, int component) {
        return switch (data) {
            case AccessorFloatData values -> values.get(index, component);
            case AccessorByteData values -> values.getInt(index, component);
            case AccessorShortData values -> values.getInt(index, component);
            case AccessorIntData values -> values.getLong(index, component);
            default -> throw new IllegalArgumentException("Unsupported accessor data");
        };
    }

    private static ModelNode node(NodeModel source, Map<MeshPrimitiveModel, String> parts,
          Map<MaterialModel, String> materials, Set<NodeModel> visited, Set<String> names) {
        require(visited.add(source), "Node hierarchy contains a cycle or repeated child");
        var result = new ModelNode();
        result.id = source.getName();
        require(result.id != null && names.add(result.id), "Node names must be unique");
        result.meshId = "geometry";
        result.translation = new Vector3();
        result.rotation = new Quaternion();
        result.scale = new Vector3(1, 1, 1);
        if (source.getMatrix() != null) {
            Matrix4 transform = new Matrix4(Z_UP).mul(new Matrix4(source.getMatrix())).mul(Y_UP);
            transform.getTranslation(result.translation);
            transform.getRotation(result.rotation, true);
            transform.getScale(result.scale);
        } else {
            float[] t = source.getTranslation(), r = source.getRotation(), s = source.getScale();
            if (t != null) { result.translation.set(t[0], -t[2], t[1]); }
            if (r != null) { result.rotation.set(r[0], -r[2], r[1], r[3]); }
            if (s != null) { result.scale.set(s[0], s[2], s[1]); }
        }
        for (float value : new Matrix4(result.translation, result.rotation, result.scale).val) {
            require(Float.isFinite(value), "Non-finite node transform");
        }
        List<ModelNodePart> nodeParts = new ArrayList<>();
        for (var mesh : source.getMeshModels()) {
            for (var primitive : mesh.getMeshPrimitiveModels()) {
                var part = new ModelNodePart();
                part.meshPartId = parts.get(primitive);
                part.materialId = materials.get(primitive.getMaterialModel());
                require(part.materialId != null, "Missing named material");
                nodeParts.add(part);
            }
        }
        result.parts = nodeParts.toArray(ModelNodePart[]::new);
        result.children = source.getChildren().stream().filter(child -> !megamek.common.board.BuildingVolumes.helper(child.getName()))
              .map(child -> node(child, parts, materials, visited, names))
              .toArray(ModelNode[]::new);
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalArgumentException(message); }
    }
}
