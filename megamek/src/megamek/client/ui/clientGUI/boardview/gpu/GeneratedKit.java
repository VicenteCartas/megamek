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
import megamek.common.Configuration;
import megamek.common.board.HexAppearance;

/**
 * A connected building kit generated from its JSON spec ({@code models/buildings/<kit>/<kit>.json}) in place of a GLB:
 * an array of named variants, the first the default ({@link megamek.common.board.TilesetScenery#kitVariants}), each
 * dressing its footprint's walls in its {@code style} ({@link HabitatKit}, {@link BayKit}) with the modelled pieces of
 * its pieces GLB, as the nodes a kit GLB holds ({@link ConnectedKit}).
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
                JsonNode chosen = variant(JSON.readTree(spec), variant);
                if (!chosen.path("pieces").isTextual()) { throw new IllegalArgumentException("a variant names its pieces GLB"); }
                var modelled = RigidGlb.loadLods(new FileHandle(new File(spec.getParentFile(), chosen.path("pieces").asText())),
                      new File(Configuration.dataDir(), "models").toPath());
                String name = spec.getName().replaceFirst("\\.json$", "");
                String style = chosen.path("style").asText();
                var kit = ConnectedKit.load(List.copyOf(switch (style) {
                    case "habitat" -> HabitatKit.lods(JSON.treeToValue(chosen, HabitatKit.Spec.class), modelled, square, name, footprintScale);
                    case "bays" -> BayKit.lods(JSON.treeToValue(chosen, BayKit.Spec.class), modelled, square, name, footprintScale);
                    default -> throw new IllegalArgumentException("a variant's style is habitat or bays, not " + style);
                }));
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
        return new ConnectedKit.Kit(kit.lods(), kit.pieces(), kit.parts(), kit.roles(), kit.symmetric(), grounds);
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
     * A roof piece's aviation obstruction lights ({@code aol} in its kit's spec): one on each convex corner of its walls
     * ({@link KitOutline#convexCorners}, arcs of facets no longer than {@code facet}) within its sector, so the piece's
     * cut never halves one; its stick from {@code deck}, the roof's height, down into the roof.
     */
    static void lights(GeneratedMesh geometry, KitOutline.Piece piece, double facet, double deck, int lod) {
        for (double[] corner : KitOutline.convexCorners(piece.walls(), facet, LIGHT_INSET)) {
            if (piece.direction() == null || KitOutline.inSector(piece.direction(), corner)) {
                light(geometry, corner, deck, lod);
            }
        }
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
            // Each piece material keeps its finish and alpha mode, as an authored GLB's do: the kit draws it under the
            // same id (GeneratedMesh#piece), a cutout alpha tested rather than blended as its board material's .tres says;
            // the aviation light's too, where the kit stands them (lights).
            List<RigidGlb.Data> sources = new ArrayList<>(List.of((RigidGlb.Data) modelled.get(lod)));
            if (deck != null) { sources.add((RigidGlb.Data) aviationLight().getFirst()); }
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
            result.add(generated);
        }
        return result;
    }

    /** A modelled piece of the kit's pieces GLB at {@code lod}, {@link GeneratedMesh#piece}. */
    static void piece(GeneratedMesh geometry, List<ModelData> modelled, int lod, String name, Matrix4 frame, double height) {
        // A level the GLB lacks repeats the one before it, under that level's names (MeshLod#load).
        var level = modelled.get(Math.min(lod, modelled.size() - 1));
        geometry.piece(level, name + "-lod" + MeshLod.level(level.id), frame, (float) height);
    }
}
