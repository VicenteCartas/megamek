/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    /** A role's module for one footprint piece at a level of detail, cut to its corner: by twin ("", "-bend"). */
    interface Module {
        Map<String, GeneratedMesh> build(String role, KitOutline.Piece piece, int lod);
    }

    /**
     * The kit of {@code spec} (a JSON file) in its Square footprint or Angled, in the variant named {@code variant} (null,
     * or a name the spec does not list: its first); it and its pieces GLB read once, until assets reload.
     */
    static ConnectedKit.Kit kit(File spec, boolean square, String variant) {
        String key = spec.getAbsolutePath() + (square ? "#square" : "") + "@" + variant;
        return KITS.computeIfAbsent(key, ignored -> new BoardKit<>(() -> {
            try {
                JsonNode chosen = variant(JSON.readTree(spec), variant);
                if (!chosen.path("pieces").isTextual()) { throw new IllegalArgumentException("a variant names its pieces GLB"); }
                var modelled = RigidGlb.loadLods(new FileHandle(new File(spec.getParentFile(), chosen.path("pieces").asText())),
                      new File(Configuration.dataDir(), "models").toPath());
                String name = spec.getName().replaceFirst("\\.json$", "");
                String style = chosen.path("style").asText();
                return ConnectedKit.load(List.copyOf(switch (style) {
                    case "habitat" -> HabitatKit.lods(JSON.treeToValue(chosen, HabitatKit.Spec.class), modelled, square, name);
                    case "bays" -> BayKit.lods(JSON.treeToValue(chosen, BayKit.Spec.class), modelled, square, name);
                    default -> throw new IllegalArgumentException("a variant's style is habitat or bays, not " + style);
                }));
            } catch (IOException | RuntimeException failure) {
                throw new IllegalArgumentException("Cannot read building kit " + spec + ": " + failure.getMessage(), failure);
            }
        })).get();
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

    static void reload() { KITS.clear(); }

    /**
     * The kit's levels of detail (its pieces GLB's): every role's module for every footprint piece, named as a kit GLB's
     * nodes; {@code boardUVs}, the materials textured on the board's axes (units a repeat).
     */
    static List<RigidGlb.Data> lods(String name, boolean square, List<ModelData> modelled, List<String> roles,
          List<KitOutline.Piece> pieces, Map<String, Float> boardUVs, Module module) {
        String kit = name + (square ? "-square" : "");
        List<RigidGlb.Data> result = new ArrayList<>();
        for (int lod = 0; lod < GpuBuilding.lods(modelled).size(); lod++) {
            Map<String, GeneratedMesh> nodes = new LinkedHashMap<>();
            for (String role : roles) {
                for (var piece : pieces) {
                    for (var twin : module.build(role, piece, lod).entrySet()) {
                        String node = kit + "-lod" + lod + "-" + role + piece.tags() + twin.getKey();
                        if (twin.getValue().parts.isEmpty()) { throw new IllegalStateException("Empty piece " + node); }
                        nodes.put(node, twin.getValue());
                    }
                }
            }
            result.add(GeneratedMesh.kit(kit + "-lod" + lod, nodes, boardUVs));
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
