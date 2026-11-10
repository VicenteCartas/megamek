/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntToDoubleFunction;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.math.Matrix4;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import megamek.common.Configuration;
import megamek.common.board.HexAppearance;
import megamek.common.board.TilesetScenery;

/**
 * A connected building kit generated from its JSON spec (a file under {@code models/buildings}: a standard kit's
 * {@code standard/<class>-<weight>.json}, a look's {@code <look>/<look>.json}) in place of a GLB: its {@code style}
 * ({@link HabitatKit}, {@link BayKit}) and pieces GLB, what its variants share ({@code shared}), its roofs by name
 * ({@code roofs}) and its named {@code variants}, the first the default
 * ({@link megamek.common.board.TilesetScenery#kitVariants}), each dressing its footprint's walls with the modelled
 * pieces of the pieces GLB, as the nodes a kit GLB holds ({@link ConnectedKit}).
 */
final class GeneratedKit {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, BoardKit<ConnectedKit.Kit>> KITS = new ConcurrentHashMap<>();

    private GeneratedKit() { }

    /** A role's module for one footprint piece at a level of detail, cut to its corner: by twin ("", "-bend", "-closed-N"). */
    interface Module {
        Map<String, GeneratedMesh> build(String role, KitOutline.Piece piece, int lod);
    }

    /**
     * The kit of {@code spec} (a JSON file) in its Square footprint or Angled, in the variant named {@code variant} (null,
     * or a name the spec does not list: its first); it and its pieces GLB read once, until assets reload.
     */
    static ConnectedKit.Kit kit(File spec, boolean square, String variant) {
        return kit(spec, square, variant, 1);
    }

    static ConnectedKit.Kit kit(File spec, boolean square, String variant, float footprintScale) {
        String key = spec.getAbsolutePath() + (square ? "#square" : "") + "@" + variant + ":" + footprintScale;
        return KITS.computeIfAbsent(key, ignored -> new BoardKit<>(() -> {
            try {
                boolean vaulted = variant != null && variant.endsWith(TilesetScenery.VAULTED);
                JsonNode chosen = resolved(JSON.readTree(spec),
                      vaulted ? variant.substring(0, variant.length() - TilesetScenery.VAULTED.length()) : variant, vaulted);
                if (!chosen.path("pieces").isTextual()) { throw new IllegalArgumentException("a kit names its pieces GLB"); }
                var modelled = RigidGlb.loadLods(new FileHandle(new File(spec.getParentFile(), chosen.path("pieces").asText())),
                      new File(Configuration.dataDir(), "models").toPath());
                String name = spec.getName().replaceFirst("\\.json$", "");
                String style = chosen.path("style").asText();
                KitDoors.Frame doors;
                List<RigidGlb.Data> lods;
                switch (style) {
                    case "habitat" -> {
                        var habitat = JSON.treeToValue(chosen, HabitatKit.Spec.class);
                        lods = HabitatKit.lods(habitat, modelled, square, name, footprintScale);
                        doors = doors(habitat.largeDoor(), square, habitat.lone().bandEnd(), footprintScale, habitat.cornerRadius(),
                              habitat.cornerSegments(), modelled);
                    }
                    case "bays" -> {
                        var bays = JSON.treeToValue(chosen, BayKit.Spec.class);
                        lods = BayKit.lods(bays, modelled, square, name, footprintScale);
                        doors = doors(bays.largeDoor(), square, bays.bandEnd(), footprintScale, bays.cornerRadius(), bays.cornerSegments(), modelled);
                    }
                    default -> throw new IllegalArgumentException("a kit's style is habitat or bays, not " + style);
                }
                var kit = ConnectedKit.load(List.copyOf(lods)).withDoors(doors);
                return style.equals("bays") ? seated(kit) : kit;
            } catch (IOException | RuntimeException failure) {
                throw new IllegalArgumentException("Cannot read building kit " + spec + ": " + failure.getMessage(), failure);
            }
        })).get();
    }

    /**
     * {@code kit} with its roofs standing on the storey under them: a bays kit lays its near roof from 0 up, its edge's
     * bevel and the deck's body reaching below, which the lowest point (ConnectedKit's ground) would lift it by. Farther
     * levels keep their place against the near one's (a lower far deck stays where it stood).
     */
    private static ConnectedKit.Kit seated(ConnectedKit.Kit kit) {
        Map<String, Float> near = kit.grounds().getFirst();
        List<Map<String, Float>> grounds = kit.grounds().stream().map(ground -> {
            Map<String, Float> seated = new HashMap<>(ground);
            seated.replaceAll((role, z) -> role.startsWith("roof") ? z - near.get(role) : z);
            return Map.copyOf(seated);
        }).toList();
        return new ConnectedKit.Kit(kit.lods(), kit.pieces(), kit.parts(), kit.roles(), kit.symmetric(), grounds, kit.doors());
    }

    /** What the kit's doors need of it ({@link KitDoors.Frame}): its footprint and its variant's door, or the plain one. */
    private static KitDoors.Frame doors(KitDoors.Style door, boolean square, double bandEnd, float footprintScale, double cornerRadius,
          int cornerSegments, List<ModelData> modelled) {
        return new KitDoors.Frame(square, bandEnd, footprintScale, cornerRadius, cornerSegments,
              door == null ? KitDoors.Style.DEFAULT : door.resolved(), modelled);
    }

    /** The storeys a variant lays: each a list of the runs the game picks among ({@code base0}, {@code base1}, ...). */
    private static final List<String> ROLES = List.of("base", "floor", "top");

    /** A run's fields a kit may state once, in {@code shared}, for all its runs. */
    private static final List<String> RUN_DEFAULTS = List.of("planar", "bay");

    /**
     * The spec {@code file}'s variant named {@code name} (else its first) as its style's spec record reads it: the
     * file's {@code shared} under the variant's own fields (objects key by key, anything else replaced; a role's object
     * in {@code shared} under each of that role's runs); its storeys' runs and its roof's as {@code modules}, a run's
     * {@code far} as its second level of detail; the roof it names among the file's {@code roofs}
     * ({@code vaulted}: its other one, where it names one); its fittings for both footprints, none under a vault;
     * colours as linear tints.
     */
    private static JsonNode resolved(JsonNode file, String name, boolean vaulted) {
        ObjectNode own = (ObjectNode) variant(file.path("variants"), name);
        JsonNode shared = file.path("shared");
        ObjectNode result = JSON.createObjectNode();
        result.set("style", file.get("style"));
        result.set("pieces", file.get("pieces"));
        for (JsonNode source : List.of(shared, own)) {
            source.fields().forEachRemaining(field -> {
                if (!ROLES.contains(field.getKey())) { merge(result, field.getKey(), field.getValue()); }
            });
        }
        result.remove("class");
        JsonNode other = result.remove("vaulted"), flat = result.remove("roof"), named = vaulted && other != null ? other : flat;
        if (named == null || !file.path("roofs").path(named.asText()).isObject()) {
            throw new IllegalArgumentException("a variant names its roof among the kit's roofs, not " + named);
        }
        ObjectNode roof = file.get("roofs").get(named.asText()).deepCopy();
        JsonNode fittings = result.remove("fittings");
        if (fittings != null && !roof.has("vault")) {
            // One list of layouts where both footprints lay the same.
            result.set("fittings", fittings.isArray()
                  ? JSON.createObjectNode().<ObjectNode>set("square", fittings).set("angled", fittings) : fittings);
        }
        if (file.path("style").asText().equals("bays")) {
            Map<String, JsonNode> defaults = new HashMap<>();
            for (String key : RUN_DEFAULTS) {
                JsonNode value = result.remove(key);
                if (value != null) { defaults.put(key, value); }
            }
            ObjectNode modules = result.putObject("modules");
            for (String role : ROLES) { runs(modules, role, own.path(role), shared.path(role), defaults); }
            runs(modules, "roof", roof.remove("runs"), null, defaults);
            result.set("roof", roof);
        } else {
            result.setAll(roof);
        }
        return linear(result);
    }

    /** {@code value} under {@code key} of {@code into}: an object into the object there, key by key; else in its place. */
    private static void merge(ObjectNode into, String key, JsonNode value) {
        if (value.isObject() && into.path(key).isObject()) {
            value.fields().forEachRemaining(field -> merge((ObjectNode) into.get(key), field.getKey(), field.getValue()));
        } else {
            into.set(key, value.deepCopy());
        }
    }

    /**
     * A role's {@code runs} as the modules {@code <role>0}, {@code <role>1}, ...: each over the role's {@code common}
     * fields, its {@code far} (a whole run of its own) as its second level of detail; both over the kit's
     * {@code defaults}.
     */
    private static void runs(ObjectNode modules, String role, JsonNode runs, JsonNode common, Map<String, JsonNode> defaults) {
        int index = 0;
        for (JsonNode run : runs == null ? List.<JsonNode>of() : runs) {
            ObjectNode near = common != null && common.isObject() ? common.deepCopy() : JSON.createObjectNode();
            near.setAll((ObjectNode) run);
            ObjectNode far = (ObjectNode) near.remove("far");
            var levels = modules.putArray(role + index++);
            levels.add(near);
            if (far != null) { levels.add(far.deepCopy()); }
            for (JsonNode level : levels) {
                defaults.forEach((key, value) -> { if (!level.has(key)) { ((ObjectNode) level).set(key, value); } });
            }
        }
    }

    /** {@code node} with every {@code tint} written as a colour ({@code #rrggbb}, sRGB) as the linear triple the records read. */
    private static JsonNode linear(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> keys = new ArrayList<>();
            object.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                JsonNode value = object.get(key);
                if (key.equals("tint") && value.isTextual()) {
                    int rgb = Integer.parseInt(value.asText().substring(1), 16);
                    var tint = object.putArray(key);
                    for (int shift = 16; shift >= 0; shift -= 8) {
                        double channel = (rgb >> shift & 255) / 255.0;
                        tint.add(channel <= .04045 ? channel / 12.92 : Math.pow((channel + .055) / 1.055, 2.4));
                    }
                } else {
                    linear(value);
                }
            }
        } else if (node.isArray()) {
            node.forEach(GeneratedKit::linear);
        }
        return node;
    }

    /** The spec's variant named {@code name}, else its first; their names distinct, each a valid appearance key. */
    private static JsonNode variant(JsonNode variants, String name) {
        if (!variants.isArray() || variants.isEmpty()) { throw new IllegalArgumentException("a spec lists its variants"); }
        Set<String> names = new HashSet<>();
        JsonNode result = variants.get(0);
        for (JsonNode listed : variants) {
            String own = listed.path("name").isTextual() ? listed.get("name").asText() : null;
            if (variants.size() > 1 && (own == null || !names.add(own))) {
                throw new IllegalArgumentException("its variants need distinct names");
            }
            // A building stores its variant's name in its appearance.
            if (own != null) { HexAppearance.ofKitVariant(own); }
            if (own != null && own.equals(name)) { result = listed; }
        }
        return result;
    }

    static void reload() {
        KITS.clear();
        aviationLight = null;
    }

    /** The aviation obstruction light ({@link #lights}), read once until assets reload. */
    private static volatile List<ModelData> aviationLight;

    private static List<ModelData> aviationLight() {
        var light = aviationLight;
        if (light == null) {
            var models = new File(Configuration.dataDir(), "models");
            var file = new FileHandle(new File(models, "buildings/fittings/aviation-light.glb"));
            light = RigidGlb.loadLods(file, models.toPath());
            aviationLight = light;
        }
        return light;
    }

    /** How far in from its walls an aviation light stands on a roof's corner. */
    private static final double LIGHT_INSET = 2.5;

    /**
     * A roof twin's aviation obstruction lights ({@code aviationLights} in its kit's spec), as its own node {@code <twin>-aol} that
     * ConnectedKit stands on a tall enough hex's roof: one on each convex corner of the twin's {@code piece}
     * ({@link KitOutline#convexCorners}, arcs of facets no longer than {@code facet}) within its sector, so the piece's
     * cut never halves one; its stick from {@code deck}, the roof's height, down into the roof.
     */
    static void lights(Map<String, GeneratedMesh> twins, String twin, KitOutline.Piece piece, double facet, double deck,
          int lod) {
        var lights = new GeneratedMesh();
        for (double[] corner : KitOutline.convexCorners(piece.walls(), facet, LIGHT_INSET)) {
            if (piece.direction() == null || KitOutline.inSector(piece.direction(), corner)) {
                light(lights, corner, deck, lod);
            }
        }
        if (!lights.parts.isEmpty()) { twins.put(twin + "-aol", lights); }
    }

    private static void light(GeneratedMesh geometry, double[] at, double deck, int lod) {
        var frame = new Matrix4().setToTranslation((float) at[0], (float) at[1], (float) deck);
        piece(geometry, aviationLight(), lod, "aol", frame, 0);
    }

    /**
     * The kit's levels of detail (its pieces GLB's): every role's module for every footprint piece, named as a kit GLB's
     * nodes; {@code boardUVs}, the materials textured on the board's axes (units a repeat).
     */
    static List<RigidGlb.Data> lods(String name, boolean square, List<ModelData> modelled, List<String> roles,
          List<KitOutline.Piece> pieces, Map<String, Float> boardUVs, Module module) {
        return lods(name, square, modelled, roles, pieces, boardUVs, module, null);
    }

    /**
     * {@code deck}: the roof's height at each level of detail where the kit stands aviation lights (null: none). An
     * Angled kit's also stand as nodes {@code aol-<side>}, one on each side's nose ({@link KitOutline#elbow}), which the
     * runtime places on a roof's outer elbows ({@link ConnectedKit#placements}).
     */
    static List<RigidGlb.Data> lods(String name, boolean square, List<ModelData> modelled, List<String> roles,
          List<KitOutline.Piece> pieces, Map<String, Float> boardUVs, Module module, IntToDoubleFunction deck) {
        String kit = name + (square ? "-square" : "");
        List<RigidGlb.Data> result = new ArrayList<>();
        for (int lod = 0; lod < GpuBuilding.lods(modelled).size(); lod++) {
            Map<String, GeneratedMesh> nodes = new LinkedHashMap<>();
            for (String role : roles) {
                for (var piece : pieces) {
                    // Exposed connections only begin above ground; base caps (and their sidewalks) are never placed.
                    if (role.startsWith("base") && piece.tags().endsWith("-cap")) { continue; }
                    for (var twin : module.build(role, piece, lod).entrySet()) {
                        String node = kit + "-lod" + lod + "-" + role + piece.tags() + twin.getKey();
                        // A cap whose closed twins carry its edge, or a distant roof's without a parapet, needs no
                        // geometry, but keeps the same node contract.
                        if (twin.getValue().parts.isEmpty() && !piece.tags().endsWith("-cap")) {
                            throw new IllegalStateException("Empty piece " + node);
                        }
                        nodes.put(node, twin.getValue());
                    }
                }
            }
            for (int side = 0; deck != null && !square && side < 6; side++) {
                var light = new GeneratedMesh();
                light(light, KitOutline.elbow(side, LIGHT_INSET), deck.applyAsDouble(lod), lod);
                nodes.put(kit + "-lod" + lod + "-aol-" + side, light);
            }
            var generated = GeneratedMesh.kit(kit + "-lod" + lod, nodes, boardUVs);
            finishes(generated, modelled, lod, deck != null);
            result.add(generated);
        }
        return result;
    }

    /**
     * Gives {@code generated}'s piece materials their finish and alpha mode, as an authored GLB's have them: the kit
     * draws each under the id its pieces GLB gives it ({@link GeneratedMesh#piece}), a cutout alpha tested rather than
     * blended as its board material's .tres says; with {@code lights}, the aviation light's too ({@link #lights}).
     */
    static void finishes(RigidGlb.Data generated, List<ModelData> modelled, int lod, boolean lights) {
        List<RigidGlb.Data> sources = new ArrayList<>(List.of((RigidGlb.Data) modelled.get(Math.min(lod, modelled.size() - 1))));
        if (lights) { sources.add((RigidGlb.Data) aviationLight().getFirst()); }
        for (var source : sources) {
            source.surfaces.forEach((id, surface) -> {
                if (surface.board() == null || !generated.surfaces.containsKey(id)) { return; }
                if (source.buildingPaint.contains(id)) { generated.buildingPaint.add(id); }
                if (source.emissions.containsKey(id)) { generated.emissions.put(id, source.emissions.get(id)); }
                if (source.ownGlows.containsKey(id)) { RigidGlb.ownGlow(generated, id, source.ownGlows.get(id)); }
                if (source.alphaTests.containsKey(id)) {
                    generated.alphaTests.put(id, source.alphaTests.get(id));
                    generated.blends.remove(id);
                }
                if (source.fades.contains(id)) { generated.fades.add(id); }
            });
        }
    }

    /** A modelled piece of the kit's pieces GLB at {@code lod}, {@link GeneratedMesh#piece}. */
    static void piece(GeneratedMesh geometry, List<ModelData> modelled, int lod, String name, Matrix4 frame, double height) {
        // A level the GLB lacks repeats the one before it, under that level's names (MeshLod#load).
        var level = modelled.get(Math.min(lod, modelled.size() - 1));
        geometry.piece(level, name + "-lod" + MeshLod.level(level.id), frame, (float) height);
    }
}
