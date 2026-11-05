/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import megamek.common.Configuration;
import org.junit.jupiter.api.Test;

/**
 * Saved boards find their models by stable id ({@link BoardModelIds}), so every board model must keep exactly one id
 * and every id its model, a GLB or a generator's ({@link BoardGenerator}). A model moved by hand fails here until its
 * line in {@code uids.json} names its new path.
 */
class BoardModelIdsTest {
    @Test
    void everyBoardModelHasOneIdAndEveryIdItsModel() throws Exception {
        Path board = Configuration.dataDir().toPath().resolve("models/board");
        var index = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
              .readTree(board.resolve("uids.json").toFile());
        Map<String, String> ids = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (var entry : index.properties()) {
            String path = entry.getValue().asText();
            assertNull(ids.put(path, entry.getKey()), "Two ids name " + path);
            // A generated asset has no file: its generator (BoardGenerator) is its model.
            if (!Files.isRegularFile(board.resolve(path + ".glb")) && BoardGenerator.of(path) == null
                  && !(path.startsWith("buildings/") && TilesetScenery.customBuildingFile(path).isFile())) {
                missing.add(entry.getKey() + ": " + path);
            }
        }
        List<String> unnamed;
        try (Stream<Path> files = Files.walk(board)) {
            unnamed = files.map(file -> board.relativize(file).toString().replace('\\', '/'))
                  .filter(path -> path.endsWith(".glb")).map(path -> path.substring(0, path.length() - ".glb".length()))
                  .filter(path -> !ids.containsKey(path)).sorted().toList();
        }
        assertTrue(missing.isEmpty(), "These ids' models moved or went. For a moved model, update its line's path "
              + "(tools/move_model.py does both); never give it a new id: " + missing);
        assertTrue(unnamed.isEmpty(), "These models have no id. A moved model takes over its old line; a new one gets an "
              + "id from tools/move_model.py --stamp: " + unnamed);
        try (Stream<Path> root = Files.list(board)) {
            assertTrue(root.noneMatch(file -> file.toString().endsWith(".glb")), "Board models live in folders, none at the root");
        }
    }
}
