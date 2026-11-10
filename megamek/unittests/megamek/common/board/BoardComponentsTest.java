/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.IndustrialElevator;
import megamek.common.Report;
import megamek.common.enums.BasementType;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardComponentsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void buildingControlsKeepPropsAssetsAndCollapsedMembers() throws Exception {
        Board board = Board.createEmptyBoard(3, 1);
        var controls = Map.of("footprint", HexAppearance.SQUARE, "kitVariant", HexAppearance.ofKitVariant("default"),
              "buildingProfile", HexAppearance.STEPPED_BUILDING);
        var asset = new HexAppearance(null, "buildings/missing-pack/hall", null, null).withUid("missing-hall");
        var appearance = new java.util.LinkedHashMap<>(controls); appearance.put("building", asset);
        board.getHex(1, 0).setAppearance(appearance);
        var prop = new BoardDecoration("tiny-authored", "prop", "buildings/debug/debug-authored", null, 0, 0, 0, false, .05,
              BoardDecoration.Placement.ground(), 0);
        board.getHex(1, 0).setDecorations(List.of(prop));
        board.getHex(2, 0).setAppearance(controls);
        var reference = new BuildingVolumes.Reference(prop.id(), new Coords(1, 0));
        board.getHex(2, 0).setBuildingReference(reference);
        Board loaded = BoardFile.readNative(encode(board));
        assertEquals(appearance, loaded.getHex(1, 0).getAppearance());
        var savedProp = loaded.getHex(1, 0).getDecorations().getFirst();
        assertEquals(prop.id(), savedProp.id()); assertEquals(prop.asset(), savedProp.asset()); assertEquals(.05, savedProp.scale());
        assertEquals(controls, loaded.getHex(2, 0).getAppearance());
        assertEquals(reference, loaded.getHex(2, 0).getBuildingReference());
    }

    @Test void missingBasementsAreUnknownAndUnknownIsOmittedWhenSaving() throws Exception {
        Board source = Board.createEmptyBoard(1, 1);
        var hex = new Hex(0, "building:2:0;bldg_cf:60;bldg_elev:3", "");
        source.setHex(0, 0, hex);
        String omitted = encode(source);
        hex.addTerrain(new Terrain(Terrains.BLDG_BASEMENT_TYPE, BasementType.UNKNOWN.ordinal()));
        assertEquals(omitted, encode(source));
        assertFalse(JSON.readTree(omitted).path("hexes").get(0).path("components").path("building").has("basement"));
        var loaded = BoardFile.readNative(omitted);
        assertEquals(BasementType.UNKNOWN, loaded.getBuildingAt(new Coords(0, 0)).getBasement(new Coords(0, 0)));
    }

    @Test void closedBuildingsIgnoreBasementsAndNeverRollOrCollapseThem() throws Exception {
        for (String appearance : List.of("\"interiors\":\"no\"", "\"appearance\":{\"variant\":\"building/glass-dome\"}")) {
            String text = """
                  {"format":"megamek-board","version":3,"size":{"width":1,"height":1},
                   "hexes":[{"at":[1,1],"elevation":0,"components":{"building":{
                     "type":"building","weight":"medium","cf":60,"height":3,"connections":[],
                     "basement":"twoDeepFeet","basementCollapsed":1,%s}}}]}
                  """.formatted(appearance);
            for (boolean visuals : List.of(true, false)) {
                Board board = visuals ? BoardFile.readNative(text)
                      : BoardFile.readTerrain(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
                Coords at = new Coords(0, 0); Hex hex = board.getHex(at);
                var building = board.getBuildingAt(at);
                assertEquals(BasementType.NONE, building.getBasement(at));
                assertFalse(building.getBasementCollapsed(at));
                assertEquals(BasementType.NONE.ordinal(), hex.terrainLevel(Terrains.BLDG_BASEMENT_TYPE));
                assertFalse(hex.isBasementLevel(-1)); assertEquals(0, hex.depth(true));
                var reports = new java.util.Vector<Report>();
                assertFalse(building.rollBasement(at, board, reports));
                building.collapseBasement(at, board, reports);
                assertTrue(reports.isEmpty());
                assertFalse(hex.containsTerrain(Terrains.BLDG_BASE_COLLAPSED));
                var saved = JSON.readTree(encode(board)).path("hexes").get(0).path("components").path("building");
                assertEquals("none", saved.path("basement").asText()); assertFalse(saved.has("basementCollapsed"));
            }
        }
    }

    @Test void shippedLunarBaseKeepsItsBuildingsAndPlacedObjectsInTheCurrentSchema() throws Exception {
        Path file = Configuration.boardsDir().toPath().resolve("Alien Worlds/32x17 Lunar Base.board2");
        var document = JSON.readTree(file.toFile());
        assertEquals(BoardFile.VERSION, document.path("version").intValue());
        Board board = BoardFile.read(file);
        assertEquals(32, board.getWidth()); assertEquals(17, board.getHeight());
        int buildings = 0, objects = 0;
        for (var entry : document.path("hexes")) {
            if (entry.path("components").has("building")) { buildings++; }
            for (var object : entry.path("decorations")) {
                objects++;
                assertFalse(object.path("uid").asText().isBlank(), object.path("asset").asText());
                assertEquals(object.path("asset").asText(), BoardModelIds.path(object.path("uid").asText(), null));
            }
        }
        assertEquals(98, buildings); assertEquals(137, objects);
        assertEquals(encode(board), encode(BoardFile.readNative(encode(board))));
    }

    @Test void elevatorPayloadsAndZoneIdsUseTheirMeaningInsteadOfHexDirections() {
        Hex source = new Hex(3);
        source.addTerrain(new Terrain(Terrains.INDUSTRIAL_ELEVATOR, -2, true, IndustrialElevator.exits(4, 100)));
        source.addTerrain(new Terrain(Terrains.SOLARIS_ELEVATOR, 7, true, 5));
        source.addTerrain(new Terrain(Terrains.DEPLOYMENT_ZONE, 0, true, 1 | (1 << 30)));
        ObjectNode node = JSON.createObjectNode(); BoardComponents.write(source, node);
        var components = node.path("components");
        var industrial = components.path("elevators").path("industrial");
        assertEquals(-2, industrial.path("bottom").intValue());
        assertEquals(4, industrial.path("top").intValue());
        assertEquals(100, industrial.path("capacity").intValue());
        assertEquals("[1,3]", components.path("elevators").path("solaris").path("rolls").toString());
        assertEquals(7, components.path("elevators").path("solaris").path("alternateElevation").intValue());
        assertEquals("[1,31]", components.path("scenario").path("deploymentZones").toString());
        assertFalse(node.toString().contains("legacyExits"));
        Hex loaded = new Hex(3); BoardComponents.read(node.path("components"), loaded, true);
        assertEquals(BoardFile.hexNode(source), BoardFile.hexNode(loaded));
    }

    @Test void missingAssetPackIdsSurviveSavingCopyingTransformingAndLaterRelocation() throws Exception {
        Board source = Board.createEmptyBoard(1, 1);
        Hex hex = source.getHex(0, 0);
        String asset = "buildings/missing-pack/hall", buildingId = "pack-hall-id", objectId = "pack-sign-id";
        hex.setAppearance(Map.of("building", new HexAppearance(null, asset, null, null).withUid(buildingId)));
        var object = new BoardDecoration("sign", "prop", "missing-pack/sign", null, 0, 0, 0, false, 1,
              BoardDecoration.Placement.ground(), 0).withUid(objectId);
        hex.setDecorations(List.of(object));
        String saved = encode(source);
        Board loaded = BoardFile.readNative(saved);
        assertEquals(saved, encode(loaded));
        var style = loaded.getHex(0, 0).getAppearance().get("building");
        assertEquals(buildingId, style.uid());
        assertEquals(buildingId, HexAppearance.withRotation(style, 45).uid());
        assertEquals(buildingId, BoardFile.fromClipboard(BoardFile.clipboard(hex)).getAppearance().get("building").uid());
        for (BoardDecoration changed : List.of(object.duplicate(), object.flip(true, false), object.withGroup("group"),
              object.withColours(BoardDecoration.Colours.of("#72949b")), object.withStretch(new BoardDecoration.Stretch(2, 1, 1)),
              object.transform(.1, .2, 45, false, 2, object.placement()), object.withAsset(object.asset()))) {
            assertEquals(objectId, changed.uid());
        }
        assertNull(object.withAsset("another/model").uid());
        try (var ids = mockStatic(BoardModelIds.class)) {
            ids.when(() -> BoardModelIds.path(buildingId, asset)).thenReturn("buildings/moved/hall");
            ids.when(() -> BoardModelIds.path(objectId, object.asset())).thenReturn("moved/sign");
            Board moved = BoardFile.readNative(saved);
            assertEquals("buildings/moved/hall", moved.getHex(0, 0).getAppearance().get("building").asset());
            assertEquals("moved/sign", moved.getHex(0, 0).getDecorations().getFirst().asset());
            assertTrue(encode(moved).contains(buildingId));
            assertTrue(encode(moved).contains(objectId));
        }
    }

    @Test void explicitDefaultAppearanceValuesNormalizeToTheGenericBuilding() throws Exception {
        for (String style : List.of("{\"rotation\":0}", "{\"colours\":[]}", "{\"colours\":[null,null]}",
              "{\"footprint\":\"angled\",\"rotation\":0,\"colours\":[]}")) {
            String text = "{\"format\":\"megamek-board\",\"version\":3,\"size\":{\"width\":1,\"height\":1},"
                  + "\"hexes\":[{\"at\":[1,1],\"elevation\":0,\"components\":{\"building\":{\"appearance\":" + style + "}}}]}";
            assertTrue(BoardFile.readNative(text).getHex(0, 0).getAppearance().isEmpty(), style);
        }
    }

    @Test void legacyBuildingsMigrateToNamedComponentsAndReloadWithoutChangingTheirRules() throws Exception {
        for (int weight = 1; weight <= 5; weight++) {
            for (int buildingClass = 0; buildingClass <= 3; buildingClass++) {
                Board source = Board.createEmptyBoard(1, 2);
                for (int y = 0; y < 2; y++) {
                    source.setHex(0, y, new Hex(2, "building:" + weight + ":" + (y == 0 ? 8 : 1)
                          + ";bldg_class:" + buildingClass + ";bldg_cf:90;bldg_elev:2;bldg_basement_type:1;bldg_armor:10", "grass"));
                }
                String saved = encode(source);
                var json = JSON.readTree(saved);
                assertEquals(3, json.path("version").intValue());
                var first = json.path("hexes").get(0);
                var building = first.path("components").path("building");
                assertEquals(weight == 5 ? "wall" : "building", building.path("type").asText());
                // A wall without a weight takes the one its CF belongs to: 90 is heavy.
                assertEquals(List.of("light", "medium", "heavy", "hardened", "heavy").get(weight - 1), building.path("weight").asText());
                assertEquals(List.of("standard", "hangar", "fortress", "gunEmplacement").get(buildingClass), building.path("class").asText());
                assertEquals(90, building.path("cf").intValue());
                assertEquals("none", building.path("basement").asText());
                assertEquals(JSON.readTree("[\"s\"]"), building.path("connections"));
                Board loaded = BoardFile.readNative(saved, true);
                for (int y = 0; y < 2; y++) {
                    Hex expected = source.getHex(0, y).duplicate();
                    if (weight == 5) { expected.addTerrain(new Terrain(Terrains.BLDG_WEIGHT, 3)); }
                    assertEquals(BoardFile.hexNode(expected), BoardFile.hexNode(loaded.getHex(0, y)));
                }
                assertEquals(saved, encode(loaded));
            }
        }
    }

    @Test void wallWeightRoundTripsWithoutGrantingInteriorsOrBasements() throws Exception {
        for (int weight = 0; weight <= 5; weight++) {
            Board source = Board.createEmptyBoard(1, 1);
            source.setHex(0, 0, new Hex(0, "building:5:0;bldg_weight:" + weight
                  + ";bldg_cf:40;bldg_elev:2;bldg_interiors:1;bldg_basement_type:6;bldg_base_collapsed:1", ""));
            String saved = encode(source);
            var component = JSON.readTree(saved).path("hexes").get(0).path("components").path("building");
            assertEquals("wall", component.path("type").asText());
            assertEquals(List.of("civilian", "light", "medium", "heavy", "hardened", "castleBrian").get(weight), component.path("weight").asText());
            for (boolean visuals : List.of(false, true)) {
                Hex loaded = BoardFile.readNative(saved, visuals).getHex(0, 0);
                assertEquals(weight, loaded.terrainLevel(Terrains.BLDG_WEIGHT));
                assertEquals(BuildingInteriors.NO, BuildingInteriors.resolve(loaded));
                assertEquals(1, loaded.terrainLevel(Terrains.BLDG_BASEMENT_TYPE));
                assertFalse(loaded.containsTerrain(Terrains.BLDG_BASE_COLLAPSED));
            }
        }
    }

    @Test void aCivilianOrCastleBrianBuildingKeepsItsWeightAndIsLightOrHardenedToTheRules() throws Exception {
        for (int weight : new int[] { 0, 5 }) {
            Board source = Board.createEmptyBoard(1, 1);
            int type = weight == 0 ? 1 : 4;
            source.setHex(0, 0, new Hex(0, "building:" + type + ":0;bldg_weight:" + weight + ";bldg_cf:40;bldg_elev:2", ""));
            String saved = encode(source);
            var component = JSON.readTree(saved).path("hexes").get(0).path("components").path("building");
            assertEquals("building", component.path("type").asText());
            assertEquals(weight == 0 ? "civilian" : "castleBrian", component.path("weight").asText());
            Hex loaded = BoardFile.readNative(saved, true).getHex(0, 0);
            assertEquals(type, loaded.terrainLevel(Terrains.BUILDING));
            assertEquals(weight, TilesetScenery.buildingWeight(loaded));
            assertEquals("buildings/standard/building-" + (weight == 0 ? "civilian" : "castle-brian"), TilesetScenery.genericBuilding(loaded));
        }
        // A weight that does not fit the type is not the building's: the type decides.
        Hex stale = new Hex(0, "building:2:0;bldg_weight:0;bldg_cf:40;bldg_elev:2", "");
        assertEquals(2, TilesetScenery.buildingWeight(stale));
    }

    @Test void rubbleStoresWhatFellApartFromItsWeight() throws Exception {
        List<String> expected = List.of("{\"type\":\"building\",\"weight\":\"light\"}", "{\"type\":\"building\",\"weight\":\"medium\"}",
              "{\"type\":\"building\",\"weight\":\"heavy\"}", "{\"type\":\"building\",\"weight\":\"hardened\"}",
              "{\"type\":\"wall\"}", "{\"type\":\"ultra\"}");
        for (int level = 1; level <= 6; level++) {
            Board source = Board.createEmptyBoard(1, 1);
            source.setHex(0, 0, new Hex(0, "rubble:" + level, ""));
            String saved = encode(source);
            assertEquals(JSON.readTree(expected.get(level - 1)), JSON.readTree(saved).path("hexes").get(0).path("components").path("rubble"));
            assertEquals(level, BoardFile.readNative(saved, true).getHex(0, 0).terrainLevel(Terrains.RUBBLE));
        }
    }

    @Test void allAuthoredTerrainFieldsAndLegacySelectorExitsHaveALosslessComponentMapping() {
        for (int type = 0; type < Terrains.SIZE; type++) {
            if (Terrains.AUTOMATIC.contains(type) || type == Terrains.BLDG_WEIGHT) { continue; }
            for (int exits : new int[] { 0, 9, 63, 255 }) {
                Hex source = new Hex();
                source.addTerrain(new Terrain(type, 2, true, exits));
                ObjectNode node = JSON.createObjectNode(); BoardComponents.write(source, node);
                Hex loaded = new Hex(); BoardComponents.read(node.get("components"), loaded, true);
                assertEquals(source.getTerrain(type).toString(), loaded.getTerrain(type).toString(), Terrains.getName(type));
            }
        }
    }

    @Test void everyConnectionMaskRoundTripsAndCalculatedSidesAreFrozenWithoutMutatingTheSource() {
        for (int type : Terrains.exitableTerrains) {
            // Water and pavement derive their sides on reading; their appearance's separated sides part them.
            if (type == Terrains.WATER || type == Terrains.PAVEMENT) { continue; }
            for (int mask = 0; mask < 64; mask++) {
                Hex source = new Hex();
                Terrain terrain = new Terrain(type, 1); terrain.setExits(mask); source.addTerrain(terrain);
                ObjectNode node = JSON.createObjectNode(); BoardComponents.write(source, node);
                Hex loaded = new Hex(); BoardComponents.read(node.get("components"), loaded, true);
                assertEquals(mask, loaded.getTerrain(type).getExits());
                assertTrue(loaded.getTerrain(type).hasExitsSpecified());
                assertFalse(terrain.hasExitsSpecified());
            }
        }
    }

    @Test void appearancesNestWithTheirOwnerAndAssetUidsSurviveRelocation() throws Exception {
        String oldPath = "buildings/colony/operations", movedPath = "buildings/colony/operations-renamed", uid = "stable-operations-id";
        Board source = Board.createEmptyBoard(1, 1);
        Hex hex = new Hex(0, "building:3:0;bldg_cf:90;bldg_elev:2;bldg_basement_type:1;bldg_class:2", "");
        var colours = new BoardDecoration.Colours(List.of("#72949B"));
        hex.setAppearance(Map.of("building", new HexAppearance(null, oldPath, null, null, colours, 45),
              "footprint", HexAppearance.SQUARE, "kitVariant", HexAppearance.ofKitVariant("parapet"),
              "doors", HexAppearance.ofDoors(new int[] { 2, 2, 1, 0, 0, 0 }, new int[] { 4, 4, 0, 0, 0, 0 }),
              "roof", HexAppearance.of(HexAppearance.Roof.FLAT)));
        source.setHex(0, 0, hex);
        String saved;
        try (var ids = mockStatic(BoardModelIds.class)) {
            ids.when(() -> BoardModelIds.uid(oldPath)).thenReturn(uid);
            saved = encode(source);
        }
        var style = JSON.readTree(saved).path("hexes").get(0).path("components").path("building").path("appearance");
        assertEquals(uid, style.path("uid").asText()); assertEquals(oldPath, style.path("asset").asText());
        assertEquals(45, style.path("rotation").intValue());
        assertEquals("square", style.path("footprint").asText());
        assertEquals("parapet", style.path("kitVariant").asText());
        assertEquals(JSON.readTree("{\"n\":2,\"ne\":2,\"se\":1}"), style.path("doors"));
        assertEquals(JSON.readTree("{\"n\":4,\"ne\":4}"), style.path("doorLinks"), "The linked doors' group");
        assertEquals("flat", style.path("roof").asText());
        assertEquals(JSON.readTree("[\"#72949b\"]"), style.path("colours"));
        try (var ids = mockStatic(BoardModelIds.class)) {
            ids.when(() -> BoardModelIds.path(uid, oldPath)).thenReturn(movedPath);
            var read = BoardFile.readNative(saved).getHex(0, 0).getAppearance();
            assertEquals(movedPath, read.get("building").asset());
            assertEquals(colours, read.get("building").colours());
            assertEquals(45, read.get("building").rotation());
            assertEquals(HexAppearance.SQUARE, read.get("footprint"));
            assertEquals("parapet", HexAppearance.kitVariant(read));
            assertArrayEquals(new int[] { 2, 2, 1, 0, 0, 0 }, HexAppearance.doors(read));
            assertArrayEquals(new int[] { 4, 4, 0, 0, 0, 0 }, HexAppearance.doorLinks(read));
            assertEquals(HexAppearance.Roof.FLAT, HexAppearance.roof(read));
        }
    }

    @Test void metadataAndTerrainPreviewsReadComponentsWithoutResolvingArtwork(@TempDir Path directory) throws Exception {
        String current = """
              {"format":"megamek-board","version":3,"size":{"width":1,"height":1},"tags":["test"],
               "hexes":[{"at":[1,1],"elevation":2,"components":{"water":{"depth":2},
               "road":{"type":"paved","connections":["n","s"]},
               "building":{"appearance":{"asset":"missing","uid":"missing-id"}}}}]}
              """;
        Path path = directory.resolve("test.board2"); Files.writeString(path, current);
        assertEquals(1, BoardFile.metadata(path).size().width());
        assertTrue(BoardFile.metadata(path).tags().contains("test"));
        try (var ids = mockStatic(BoardModelIds.class)) {
            var board = BoardFile.readTerrain(new ByteArrayInputStream(current.getBytes(StandardCharsets.UTF_8)));
            assertEquals(2, board.getHex(0, 0).terrainLevel(Terrains.WATER));
            assertEquals(9, board.getHex(0, 0).getTerrain(Terrains.ROAD).getExits());
            assertTrue(board.getHex(0, 0).getAppearance().isEmpty());
            ids.verifyNoInteractions();
        }
    }

    private static String encode(Board board) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); BoardFile.write(board, output);
        return output.toString(StandardCharsets.UTF_8);
    }
}
