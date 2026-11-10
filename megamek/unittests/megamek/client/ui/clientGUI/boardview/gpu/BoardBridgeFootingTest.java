/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardBridgeFootingTest {
    @Test
    void airportTerminalFollowsTheWholeRailProfileAtItsRearJoin() throws Exception {
        var scene = BoardScenes.installedScene("unofficial/Derv_Maps/25x25 Airport 3000.board");
        var bridge = scene.tile(new Coords(15, 11));
        // Inner rear corner of the reported cap: the rail approaches from negative forward. The wider roads moved the
        // terminal 8.6 px forward and 4.9 px out from where the report found it, and the junction beyond now keeps its
        // level, so the rail no longer falls here: the cap's lip and its cover of the rail's outer edge above ground
        // still hold.
        var rear = new Vector3(935.6337f, -870.3864f, 0);
        var forward = new Vector3(-.868243f, -.496139f, 0).nor();
        var outward = new Vector3(-.50077f, .86558f, 0).nor();
        for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
            var surfaces = new HashMap<Coords, BoardSurface>();
            var footing = BoardBridgeFooting.build(scene, bridge, lod, surfaces);
            for (float across : new float[] { .4f, 1.5f, 2.8f }) {
                var railTop = new Vector3(rear).mulAdd(forward, -.02f).mulAdd(outward, across);
                railTop.z = 100;
                var capTop = new Vector3(railTop).mulAdd(forward, .04f);
                float railHeight = 100 - (float) Math.sqrt(footing.shape().hit(new Ray(railTop, new Vector3(0, 0, -1))));
                float capHeight = 100 - (float) Math.sqrt(footing.shape().hit(new Ray(capTop, new Vector3(0, 0, -1))));
                assertEquals(.28f, capHeight - railHeight, .1f,
                      lod + " retains the authored cap lip across the rail, without a raised wedge");
            }
            var rail = new Vector3(rear).mulAdd(forward, -.02f).mulAdd(outward, 2.8f);
            rail.z = -100;
            float bottom = rail.z + (float) Math.sqrt(footing.shape().hit(new Ray(rail, Vector3.Z)));
            assertTrue(Float.isFinite(bottom), "Sample the actual underside of the rail before its cap");
            for (float along : new float[] { .05f, .2f }) {
                var outside = new Vector3(rear).mulAdd(forward, along).mulAdd(outward, 5.5f);
                // A rail edge below the ground beside it is buried: nothing protrudes there.
                double ground = surfaces.values().stream()
                      .mapToDouble(s -> BoardSurface.sampleHeight(s.foundation(), outside.x, outside.y, Float.NaN))
                      .filter(Double::isFinite).max().orElse(Double.NEGATIVE_INFINITY);
                outside.z = (float) Math.max(bottom, ground) + .1f;
                var ray = new Ray(outside, new Vector3(outward).scl(-1));
                assertTrue(footing.shape().hit(ray) < 1.2f * 1.2f,
                      lod + " the cap covers the lower outer rail edge instead of leaving a protruding wedge");
            }
        }
    }

    @Test
    void airportLevelBridgesMeetTheWholeJunctionMouthWithoutBuriedRoadSlabs() throws Exception {
        var scene = BoardScenes.installedScene("unofficial/Derv_Maps/25x25 Airport 3000.board");
        var junction = scene.tile(new Coords(14, 12));
        assertEquals(63, junction.roadExits());
        float scale = BoardGeometry.hexScale();
        for (boolean classic : new boolean[] { false, true }) {
            for (var lod : List.of(TerrainLod.FULL, TerrainLod.DISTANT)) {
                var surfaces = new HashMap<Coords, BoardSurface>();
                for (int direction : new int[] { 1, 2 }) {
                    var tile = scene.tile(junction.coords().translated(direction));
                    var deck = BoardBridge.deck(scene, tile);
                    var footing = BoardBridgeFooting.build(scene, tile, lod, surfaces, classic);
                    var shape = footing.withDeck(scene, tile, deck, lod, surfaces, classic);
                    var bank = surfaces.get(junction.coords());
                    assertNotNull(bank);
                    int edge = Math.floorMod(1 - direction, 6);
                    var a = BoardGeometry.corner(junction.coords(), 0, edge);
                    var b = BoardGeometry.corner(junction.coords(), 0, edge + 1);
                    var tangent = new Vector3(b).sub(a).nor();
                    var inward = new Vector3(-tangent.y, tangent.x, 0);
                    var gate = new Vector3(a).lerp(b, .5f);
                    if (inward.dot(BoardGeometry.center(junction.coords(), 0).sub(gate)) < 0) { inward.scl(-1); }
                    float half = BoardSurface.roadMouth(junction, tile, direction) * scale;
                    float height = BoardSurface.roadEdgeElevation(junction, tile, direction) * BoardGeometry.level();
                    String context = (classic ? "Classic " : "Enhanced ") + lod + " " + tile.coords().getBoardNum();
                    for (int station = -8; station <= 8; station++) {
                        var point = new Vector3(gate).mulAdd(tangent, (half - .1f * scale) * station / 8);
                        var onBank = new Vector3(point).mulAdd(inward, .02f * scale);
                        var onDeck = new Vector3(point).mulAdd(inward, -.02f * scale);
                        assertEquals(height, BoardSurface.sampleHeight(bank.foundation(), onBank.x, onBank.y, Float.NaN),
                              .08f * scale, context + " reserves the complete bridge passage at " + station);
                        if (Math.abs((half - .1f * scale) * station / 8) < BoardRoad.Kind.PAVED.halfWidth * scale) {
                            assertEquals(height + GpuRoads.SURFACE_LIFT * scale, BoardBridge.top(shape, onDeck.x, onDeck.y),
                                  .008f * scale, context + " joins the supported deck at " + station);
                        }
                    }
                    for (var face : footing.shape().facets()) {
                        if (face.part() != BoardBridge.Part.TOP) { continue; }
                        var point = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
                        float ground = BoardSurface.sampleHeight(bank.foundation(), point.x, point.y, Float.NaN);
                        if (!Float.isFinite(ground)) { continue; }
                        // A junction keeps its crossing level: a level landing lies flush on its pavement. A graded
                        // bank owns its road surface, and a second slab would intersect the grade.
                        assertEquals(0, bank.ramps, context + " must not bury a second painted landing inside a graded bank");
                        assertEquals(ground + GpuRoads.SURFACE_LIFT * scale, point.z, .002f * scale,
                              context + " lays its level landing flush on the junction's pavement");
                    }
                }
            }
        }
    }

    @Test
    void classicBridgeBorrowsSharpBanksAndPierFoundationsOutsideItsChunk() throws Exception {
        var scene = BoardScenes.installedScene("AGoAC Maps/16x17 Grassland 2 (Low Bridges).board");
        var tile = scene.tile(new Coords(8, 7));
        var surfaces = new HashMap<Coords, BoardSurface>();
        // Start empty, as happens when a ground rail borrows a bridge from the adjacent chunk.
        var footing = BoardBridgeFooting.build(scene, tile, TerrainLod.FULL, surfaces, true);
        var shape = footing.withDeck(scene, tile, BoardBridge.deck(scene, tile), TerrainLod.FULL, surfaces, true);
        assertFalse(shape.facets().isEmpty());
        assertFalse(surfaces.isEmpty());
        for (var source : surfaces.values()) {
            assertTrue(source.columns(), "An off-chunk support must not construct Enhanced relief");
            var center = BoardGeometry.center(source.tile.coords(), source.tile.elevation());
            assertEquals(BoardGeometry.groundZ(source.tile), source.height(center.x, center.y), .006f);
            for (var face : source.walls(scene, BoardGeometry.floor(scene), surfaces)) {
                var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                assertEquals(0, normal.z, .001f, "Classic banks remain vertical beside the bridge");
            }
        }
    }

    @Test
    void plaineBanksBesideTheDescendingBridgeApproachKeepTheirNaturalMaterial() throws Exception {
        var scene = BoardScenes.installedScene("unofficial/Cartes/16x17 Plaine 1.board");
        var tile = scene.tile(new Coords(9, 11));
        var bridge = scene.tile(new Coords(9, 10));
        assertTrue(BoardSurface.roadEdgeElevation(tile, bridge, 0) < tile.elevation(),
              "This is a cut down to a lower bridge, not raised approach fill");
        var surfaces = new HashMap<Coords, BoardSurface>();
        for (var neighbor : scene.tiles()) {
            if (tile.coords().distance(neighbor.coords()) <= 1) {
                surfaces.put(neighbor.coords(), new BoardSurface(scene, neighbor));
            }
        }
        var terrain = new HashMap<BoardSurface, List<BoardSurface.Face>>();
        for (var surface : surfaces.values()) {
            var faces = new ArrayList<>(surface.groundFaces());
            faces.addAll(surface.walls(scene, BoardGeometry.floor(scene), surfaces));
            terrain.put(surface, faces);
        }
        // Fixed world positions of the stray white pieces in the reported oblique view:
        // the left toe, the exposed bank beside the support, and the detached lower-right toe.
        // A removed toe exposes the neighboring hex, whose terrain owns its material decision.
        boolean hitApproachBank = false;
        for (var point : List.of(new Vector3(633.066f, -817.937f, .210f),
              new Vector3(590.961f, -827.158f, 5.250f), new Vector3(585.089f, -819.141f, .558f))) {
            var ray = new Ray(new Vector3(point).add(-20, 20, 20), new Vector3(1, -1, -1).nor());
            BoardSurface.Face visible = null;
            BoardSurface owner = null;
            float distance = Float.POSITIVE_INFINITY;
            var hit = new Vector3();
            for (var entry : terrain.entrySet()) {
                for (var face : entry.getValue()) {
                    var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                    if (normal.dot(ray.direction) < 0
                          && Intersector.intersectRayTriangle(ray, face.a(), face.b(), face.c(), hit)
                          && ray.origin.dst2(hit) < distance) {
                        visible = face;
                        owner = entry.getKey();
                        distance = ray.origin.dst2(hit);
                    }
                }
            }
            assertNotNull(visible, "The natural bank remains closed at " + point);
            assertFalse(owner.bridgeSupport(visible), "A cut bank must not become a detached concrete piece: " + point);
            hitApproachBank |= owner.tile.coords().equals(tile.coords());
        }
        assertTrue(hitApproachBank, "The rays exercise the descending approach's own exposed bank");
    }

    @Test
    void convertedLegacyBridgePaintStaysAboveTheSlabAndBothBankRoads() throws Exception {
        var board = BoardFile.loadSheet(new File(Configuration.boardsDir(),
              "GrassLands/16x17 Grasslands River CommCenter.board"), false, true);
        Coords at = new Coords(7, 14);
        assertEquals(1, board.getHex(at).terrainLevel(Terrains.BRIDGE_ROAD),
              "The production legacy conversion must infer the connected paved deck road");
        var scene = BoardScenes.scene(board);
        var tile = scene.tile(at);
        assertEquals(BoardRoad.Kind.PAVED, tile.deckRoad());
        var deck = BoardBridge.deck(scene, tile);
        assertEquals(deck.exits(), deck.roadExits());
        var surfaces = new HashMap<Coords, BoardSurface>();
        var footing = BoardBridgeFooting.build(scene, tile, TerrainLod.FULL, surfaces);
        var shape = footing.withDeck(scene, tile, deck, TerrainLod.FULL, surfaces);
        var painted = footing.paint(deck);
        var road = painted.road(deck, at);
        var patches = GpuRoads.deckPatches(tile, deck, road, painted);
        // The opaque core: its only fade carries the traffic wear (GpuRoads.patches).
        var asphalt = patches.stream().filter(p -> p.texture().equals("roads/asphalt")
              && (p.fade() == null || p.fade().wheels())).findFirst().orElseThrow();
        var marks = patches.stream().filter(p -> p.texture().equals("concrete-ground")).findFirst().orElseThrow();
        var coat = GpuRoads.deck(tile, BoardBridge.feature(tile), asphalt, painted, surfaces, shape);
        var paint = GpuRoads.deck(tile, BoardBridge.feature(tile), marks, painted, surfaces, shape);
        float scale = BoardGeometry.hexScale();
        var center = BoardGeometry.center(at, tile.elevation() + BoardBridge.feature(tile).elevation());
        for (int direction : new int[] { 2, 5 }) {
            var bank = scene.tile(at.translated(direction));
            assertEquals(BoardRoad.Kind.PAVED, bank.road(), "Both real bank contacts have connected roads");
            var surface = surfaces.computeIfAbsent(bank.coords(), key -> new BoardSurface(scene, bank));
            var bankRoad = BoardRoad.of(scene, bank);
            var bankAsphalt = GpuRoads.patches(bank, bankRoad).stream()
                  .filter(p -> p.texture().equals("roads/asphalt") && (p.fade() == null || p.fade().wheels()))
                  .findFirst().orElseThrow();
            var groundCoat = GpuRoads.drape(bank, surface, bankAsphalt);
            var axis = BoardGeometry.center(bank.coords(), 0).sub(BoardGeometry.center(at, 0)).nor();
            var side = new Vector3(-axis.y, axis.x, 0);
            var gate = new Vector3(center).lerp(BoardGeometry.center(bank.coords(), bank.elevation()), .5f);
            int supported = 0, joined = 0, marked = 0;
            for (float along = 0; along <= footing.lengths().get(direction) * scale; along += .5f * scale) {
                for (float lateral : new float[] { -2, -.25f, 0, .25f, 2 }) {
                    var point = new Vector3(gate).mulAdd(axis, along).mulAdd(side, lateral * scale);
                    float x = (point.x - center.x) / scale, y = (point.y - center.y) / scale;
                    if (!asphalt.shape().contains(x, y)) { continue; }
                    float top = triangleHeight(coat, point), slab = BoardBridge.top(shape, point.x, point.y);
                    if (!Float.isFinite(top) || !Float.isFinite(slab)) { continue; }
                    assertTrue(top > slab + .005f * scale, "The opaque deck coat clears the supporting slab");
                    supported++;
                    float bx = (point.x - BoardGeometry.centerX(bank.coords())) / scale;
                    float by = (point.y - BoardGeometry.centerY(bank.coords())) / scale;
                    float ground = triangleHeight(groundCoat, point);
                    if (bankAsphalt.shape().contains(bx, by) && Float.isFinite(ground)) {
                        assertTrue(top > ground + .005f * scale, "Overlapping bank asphalt cannot fight the deck coat");
                        joined++;
                    }
                    if (marks.shape().contains(x, y)) {
                        assertTrue(triangleHeight(paint, point) > top + .005f * scale,
                              "Road markings stay above the opaque base coat at the landing");
                        marked++;
                    }
                }
            }
            assertTrue(supported > 0 && joined > 0 && marked > 0,
                  "Exercise the slab, real bank-road overlap and markings at each end: " + direction);
        }
    }

    private static float triangleHeight(List<BoardTacticalGeometry.Triangle> triangles, Vector3 point) {
        float height = Float.NEGATIVE_INFINITY;
        for (var triangle : triangles) {
            height = Math.max(height, new BoardSurface.Face(triangle.a(), triangle.b(), triangle.c(),
                  BoardSurface.Finish.TOP).height(point.x, point.y));
        }
        return height;
    }

    @Test
    void reloadingAnEditedGlbChangesTheBankExtension(@TempDir Path directory) throws Exception {
        var scene = BridgeScenes.canyonScene(BoardScene.Surface.GRASS, false, true);
        var tile = scene.tile(BoardScenes.CENTER);
        var surfaces = new HashMap<Coords, BoardSurface>();
        var before = BoardBridgeFooting.build(scene, tile, TerrainLod.FULL, surfaces);
        float length = BoardBridgeFooting.terminalLength();
        var originalData = Configuration.dataDir();
        var source = originalData.toPath().resolve("models/board");
        var target = Files.createDirectories(directory.resolve("models/board"));
        // The concrete material (its Godot 4 Standard resource and maps) with the catalogue of loose materials.
        var concrete = Files.createDirectories(target.resolve("textures/terrain/surfaces"));
        Files.copy(source.resolve("textures/materials.json"), target.resolve("textures/materials.json"));
        try (var files = Files.list(source.resolve("textures/terrain/surfaces"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().matches("concrete-ground(\\.tres|_.*\\.png)")).toList()) {
                Files.copy(file, concrete.resolve(file.getFileName()));
            }
        }
        // The reload also rereads the pier kits, the bridge's and the maglev's.
        Files.createDirectories(target.resolve("bridges"));
        Files.copy(source.resolve("bridges/bridge-pier.glb"), target.resolve("bridges/bridge-pier.glb"));
        Files.copy(source.resolve("bridges/maglev-pier.glb"), target.resolve("bridges/maglev-pier.glb"));
        byte[] bytes = Files.readAllBytes(source.resolve("bridges/bridge-terminal.glb"));
        int jsonLength = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(12);
        var mapper = new ObjectMapper();
        var document = mapper.readTree(new String(bytes, 20, jsonLength, StandardCharsets.UTF_8));
        for (var node : document.get("nodes")) {
            // glTF Z is the block's longitudinal board axis; both LODs retain the same footprint.
            ((ObjectNode) node).set("scale", mapper.createArrayNode().add(1).add(1).add(1.5));
        }
        byte[] json = mapper.writeValueAsBytes(document);
        int padded = (json.length + 3) & ~3;
        var output = ByteBuffer.allocate(bytes.length - jsonLength + padded).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(0x46546c67).putInt(2).putInt(output.capacity()).putInt(padded).putInt(0x4e4f534a).put(json);
        while (output.position() < 20 + padded) { output.put((byte) ' '); }
        output.put(bytes, 20 + jsonLength, bytes.length - 20 - jsonLength);
        Files.write(target.resolve("bridges/bridge-terminal.glb"), output.array());
        try {
            Configuration.setDataDir(directory.toFile());
            BoardBridgeFooting.reload();
            assertEquals(length * 1.5f, BoardBridgeFooting.terminalLength(), .001f);
            for (var lod : TerrainLod.values()) {
                var after = BoardBridgeFooting.build(scene, tile, lod, surfaces);
                for (int d : new int[] { 0, 3 }) {
                    assertEquals(before.lengths().get(d) + length * .5f, after.lengths().get(d), .001f,
                          "Actual GLB dimensions, including after reload, determine the placed block and footing");
                }
            }
        } finally {
            Configuration.setDataDir(originalData);
            BoardBridgeFooting.reload();
        }
    }

    @Test
    void everyTerminalReachesSolidBankAtAllOrientationsAndLods() {
        for (int x : new int[] { 3, 4 }) {
            var at = new Coords(x, 4);
            for (int d = 0; d < 6; d++) {
                var scene = BridgeScenes.canyonScene(BoardScene.Surface.GRASS, false, true, at, d);
                int exits = (1 << d) | (1 << ((d + 3) % 6));
                scene = BoardScenes.replace(scene,
                      BoardScenes.tile(at.translated(d), BoardRoad.Kind.PAVED, exits, 3, BoardScene.Surface.GRASS));
                var tile = scene.tile(at);
                for (var lod : TerrainLod.values()) {
                    var surfaces = new HashMap<Coords, BoardSurface>();
                    var footing = BoardBridgeFooting.build(scene, tile, lod, surfaces);
                    for (int direction : new int[] { d, (d + 3) % 6 }) {
                        float reach = footing.lengths().get(direction) * BoardGeometry.hexScale();
                        assertTrue(reach >= BoardRelief.metres(1), "Even level road banks get a real overlap");
                        var center = BoardGeometry.center(at, 3);
                        var along = BoardGeometry.center(at.translated(direction), 3).sub(center);
                        var gate = new Vector3(center).mulAdd(along, .5f);
                        along.nor();
                        var across = new Vector3(-along.y, along.x, 0);
                        var bank = surfaces.get(at.translated(direction));
                        var ground = bank.faces.stream().filter(f -> f.finish() != BoardSurface.Finish.OUTCROP).toList();
                        for (int side : new int[] { -1, 1 }) {
                            float lateral = 18 * BoardGeometry.hexScale() + BoardRelief.metres(.15f);
                            var cap = new Vector3(gate).mulAdd(along, reach - BoardRelief.metres(1.38f));
                            float deckHeight = height(footing.shape(), cap);
                            cap.mulAdd(across, side * lateral);
                            float capHeight = height(footing.shape(), cap);
                            assertTrue(capHeight > deckHeight + 2.5f * BoardGeometry.hexScale() + BoardRelief.metres(.04f),
                                  "Both bank ends have separate raised blocks outside the original rails, at every LOD");
                            var toe = new Vector3(gate).mulAdd(along, reach - BoardRelief.metres(.05f))
                                  .mulAdd(across, side * lateral);
                            float support = BoardSurface.sampleHeight(ground, toe.x, toe.y, Float.NaN);
                            float toeHeight = height(footing.shape(), toe);
                            assertTrue(Float.isFinite(support) && toeHeight >= support
                                  && toeHeight < support + BoardRelief.metres(.3f), "The widened sloping nose has a grounded toe");
                            assertTrue(bank.relief.clearance(cap.x, cap.y) >= BoardRelief.metres(.3f),
                                  "Terminal blocks start beyond the cliff opening");
                            var passage = new Vector3(gate).mulAdd(along, reach - BoardRelief.metres(1.38f))
                                  .mulAdd(across, side * 7.25f * BoardGeometry.hexScale());
                            assertEquals(deckHeight, height(footing.shape(), passage), BoardRelief.metres(.05f),
                                  "The thicker end blocks preserve the carriageway width");
                        }
                        for (int side = -3; side <= 3; side++) {
                            var end = new Vector3(gate).mulAdd(along, reach - BoardRelief.metres(.05f))
                                  .mulAdd(across, side * 2 * BoardGeometry.hexScale());
                            float support = BoardSurface.sampleHeight(ground, end.x, end.y, Float.NaN);
                            assertTrue(Float.isFinite(support), "The bank, not a loose rock, supports the far end");
                            var ray = new Ray(new Vector3(end).add(0, 0, 40), new Vector3(0, 0, -1));
                            float deck = ray.origin.z - (float) Math.sqrt(footing.shape().hit(ray));
                            assertEquals(support, deck, BoardRelief.metres(.12f), "Deck meets the bank across its width");
                            for (int step = 1; step < 12; step++) {
                                var p = new Vector3(gate).mulAdd(along, reach * step / 12)
                                      .mulAdd(across, side * 2 * BoardGeometry.hexScale());
                                assertTrue(Float.isFinite(footing.shape().hit(new Ray(p.add(0, 0, 40), new Vector3(0, 0, -1)))),
                                      "No hole between the GLB and its bank extension");
                            }
                        }
                    }
                    // Both banks fall away below their landings: each abutment adds four sides, eight facets.
                    int budget = (lod == TerrainLod.FULL || lod == TerrainLod.MEDIUM ? 440 : 248) + 2 * 8;
                    assertTrue(footing.shape().facets().size() <= budget, "Close LODs bevel the caps; distant LODs keep the silhouette");
                }
            }
        }
    }

    @Test
    void aLevelBuiltLandingReachesTheNaturalBankInsteadOfPullingItUnderTheBridge() {
        var geometry = BoardGeometry.tuning();
        var relief = BoardRelief.tuning();
        try {
            BoardGeometry.tune(BoardGeometry.DEFAULTS);
            BoardRelief.tune(BoardRelief.DEFAULTS);
            Coords bridge = new Coords(3, 4), road = new Coords(4, 5);
            BoardScene plain = BoardRiverTest.commCenterRiverScene();
            var hex = new Hex(0, "water:1;bridge:4:36;bridge_cf:120;bridge_elev:1", "");
            var bridgeTile = new BoardScene.Tile(bridge, 0, 1, false, 0, BoardScene.Surface.GRASS, null, null,
                  BoardFeatures.capture(hex, bridge, Map.of()), List.of(),
                  BoardLiquid.WATER, null, true);
            var bare = BoardScenes.replace(plain, bridgeTile);
            var scene = BoardScenes.replace(bare, withRoad(bare.tile(road), 36));
            var natural = BridgeScenes.typed(scene, HexAppearance.NATURAL_BRIDGE);
            var bank = new BoardSurface(scene, scene.tile(road));
            var bareBank = new BoardSurface(bare, bare.tile(road));
            var naturalBank = new BoardSurface(natural, natural.tile(road));
            var water = new BoardSurface(scene, scene.tile(bridge));
            int edge = Math.floorMod(1 - road.direction(bridge), 6), opposite = (edge + 3) % 6;
            float top = BoardGeometry.groundZ(scene.tile(road));
            for (float z : new float[] { top, top - BoardRelief.metres(.25f), top - BoardRelief.metres(.75f), 0 }) {
                for (float t : new float[] { 1f / 3, .5f, 2f / 3 }) {
                    Vector3 actual = bank.relief.outlinePoint(edge, t, z);
                    assertTrue(actual.epsilonEquals(bareBank.relief.outlinePoint(edge, t, z), .002f),
                          "The built landing leaves the receding natural bank's profile intact");
                    assertTrue(actual.epsilonEquals(water.relief.outlinePoint(opposite, 1 - t, z), .002f),
                          "Both sides retain one shared bank wall without a land/water crack");
                    // A road without a bridge has a footing out to its verge over the drop to water instead.
                    assertTrue(naturalBank.relief.outlinePoint(edge, t, z)
                          .epsilonEquals(bareBank.relief.outlinePoint(edge, t, z), .002f),
                          "A natural bridge's mouth keeps its existing profile");
                }
            }
            for (float t : new float[] { 1f / 3, .5f, 2f / 3 }) {
                assertTrue(bank.relief.outlinePoint(edge, t, top)
                      .dst(bank.relief.outlinePoint(edge, t, top - .001f)) < .01f,
                      "The supported bank meets its rim continuously instead of snapping under the deck");
                float aboveRoad = top + BoardRelief.metres(1);
                assertTrue(bank.relief.outlinePoint(edge, t, aboveRoad)
                      .epsilonEquals(bareBank.relief.outlinePoint(edge, t, aboveRoad), .002f),
                      "Relief above the road keeps the bank's own profile around the bridge passage");
            }
            for (int k = 0; k < 6; k++) {
                float[] actual = bank.relief.shoreShift(k), expected = bareBank.relief.shoreShift(k);
                assertEquals(expected[0], actual[0], .002f, "Landing clearance cannot move the shared coast corners");
                assertEquals(expected[1], actual[1], .002f);
            }

            var surfaces = new HashMap<Coords, BoardSurface>();
            surfaces.put(road, bank);
            var footing = BoardBridgeFooting.build(scene, scene.tile(bridge), TerrainLod.FULL, surfaces);
            int direction = bridge.direction(road);
            float reach = footing.lengths().get(direction) * BoardGeometry.hexScale();
            var center = BoardGeometry.center(bridge, 1);
            var along = BoardGeometry.center(road, 1).sub(center);
            var gate = new Vector3(center).mulAdd(along, .5f);
            along.nor();
            var across = new Vector3(-along.y, along.x, 0);
            for (int side : new int[] { -1, 0, 1 }) {
                var toe = new Vector3(gate).mulAdd(along, reach - BoardRelief.metres(.05f))
                      .mulAdd(across, side * (18 * BoardGeometry.hexScale() + BoardRelief.metres(.15f)));
                float support = BoardSurface.sampleHeight(bank.foundation(), toe.x, toe.y, Float.NaN);
                assertTrue(Float.isFinite(support), "The bridge's widened terminal reaches the actual natural bank");
                float height = height(footing.shape(), toe);
                assertTrue(height >= support && height < support + BoardRelief.metres(.3f),
                      "The bridge terminal sits on the bank without moving the terrain towards it");
            }
        } finally {
            BoardRelief.tune(relief);
            BoardGeometry.tune(geometry);
        }
    }

    @Test
    void oneLevelBankDifferencesSeatTheSlabAndItsTaperedRails() {
        var at = BoardScenes.CENTER;
        for (int level : new int[] { 2, 4 }) {
            var scene = BridgeScenes.canyonScene(BoardScene.Surface.GRASS, false, true);
            scene = BoardScenes.replace(scene,
                  BoardScenes.tile(at.translated(0), BoardRoad.Kind.PAVED, 9, 3, BoardScene.Surface.GRASS));
            scene = BoardScenes.replace(scene,
                  BoardScenes.tile(at.translated(3), BoardRoad.Kind.NONE, 0, level, BoardScene.Surface.GRASS));
            var surfaces = new HashMap<Coords, BoardSurface>();
            var footing = BoardBridgeFooting.build(scene, scene.tile(at), TerrainLod.FULL, surfaces);
            var bank = surfaces.get(at.translated(3));
            var ground = bank.faces.stream().filter(f -> f.finish() != BoardSurface.Finish.OUTCROP).toList();
            float scale = BoardGeometry.hexScale();
            var end = BoardGeometry.center(at, 3).lerp(BoardGeometry.center(at.translated(3), 3), .5f)
                  .add(0, -footing.lengths().get(3) * scale + BoardRelief.metres(.01f), 0);
            for (float lateral : new float[] { 0, -16.5f, 16.5f }) {
                var p = new Vector3(end).add(lateral * scale, 0, 0);
                float support = BoardSurface.sampleHeight(ground, p.x, p.y, Float.NaN);
                var ray = new Ray(new Vector3(p).add(0, 0, BoardGeometry.level() * 3), new Vector3(0, 0, -1));
                float height = ray.origin.z - (float) Math.sqrt(footing.shape().hit(ray));
                assertEquals(support + .04f * scale + (lateral == 0 ? 0 : BoardRelief.metres(.08f)), height,
                      BoardRelief.metres(.12f), "The low block toes seat on the supported slab on either grade");
            }
        }
    }

    /** The scene with the deck at {@code at} carrying a road of {@code kind} over all its exits. */
    private static BoardScene paved(BoardScene scene, Coords at, BoardRoad.Kind kind) {
        var tile = scene.tile(at);
        return BoardScenes.replace(scene, BridgeScenes.withDeckRoad(tile, kind,
              BoardBridge.feature(tile).bridgeExits()));
    }

    @Test
    void bridgeToBridgeJoinsDoNotGrow() {
        var scene = BridgeScenes.straight(new Coords(4, 4), 3, 3, BoardRoad.Kind.PAVED, BoardRoad.Kind.DIRT,
              BoardRoad.Kind.PAVED);
        var middle = scene.tile(new Coords(4, 5));
        var footing = BoardBridgeFooting.build(scene, middle, TerrainLod.FULL, new HashMap<>());
        assertTrue(footing.shape().facets().isEmpty());
        assertTrue(footing.lengths().stream().allMatch(length -> length == 0));
    }

    @Test
    void piersStandOnlyUnderDeckJointsAndBothHexesBuildTheirOwnHalf() {
        // A paved span of three over water, toggled: two joints; the middle hex holds a half of each, an end hex one.
        var scene = BridgeScenes.span(3, true, 0, Set.of(0, 1, 2));
        var halves = new HashMap<Coords, List<BoardBridge.Facet>>();
        for (int i = 0; i < 3; i++) {
            Coords at = BridgeScenes.START.translated(3, i);
            halves.put(at, BridgeScenes.piers(scene, at));
            for (var facet : halves.get(at)) {
                for (var p : List.of(facet.a(), facet.b(), facet.c())) {
                    for (var tile : scene.tiles()) {
                        assertTrue(Vector3.dst(p.x, p.y, 0, BoardGeometry.centerX(tile.coords()), BoardGeometry.centerY(tile.coords()), 0)
                              >= 29.5f * BoardGeometry.hexScale() - .01f, "No pier near a hex centre, where a unit stands");
                    }
                    var owner = BoardGeometry.tile(scene, p.x - (p.x - BoardGeometry.centerX(at)) * .001f,
                          p.y - (p.y - BoardGeometry.centerY(at)) * .001f);
                    assertEquals(at, owner.coords(), "Each hex builds only the half on its own side");
                }
            }
        }
        assertEquals(List.of(1, 2, 1), List.of(joints(halves.get(BridgeScenes.START), BridgeScenes.START),
              joints(halves.get(BridgeScenes.START.translated(3)), BridgeScenes.START.translated(3)),
              joints(halves.get(BridgeScenes.START.translated(3, 2)), BridgeScenes.START.translated(3, 2))));
        for (int i = 0; i < 2; i++) {
            Coords near = BridgeScenes.START.translated(3, i), far = near.translated(3);
            var edge = BoardGeometry.center(near, 0).lerp(BoardGeometry.center(far, 0), .5f);
            var toward = BoardGeometry.center(far, 0).sub(BoardGeometry.center(near, 0)).nor();
            var mine = vertices(halves.get(near), edge);
            var theirs = vertices(halves.get(far), edge);
            assertFalse(mine.isEmpty());
            var bounds = new com.badlogic.gdx.math.collision.BoundingBox().inf();
            for (var p : mine) {
                bounds.ext(p);
                // The neighbour's half is this half's mirror image across the shared edge.
                var mirror = new Vector3(p).mulAdd(toward, -2 * new Vector3(p).sub(edge).dot(toward));
                assertTrue(theirs.stream().anyMatch(q -> q.dst(mirror.x, mirror.y, p.z) < .01f), "Mirrored: " + p);
            }
            theirs.forEach(bounds::ext);
            assertEquals(edge.x, bounds.getCenterX(), .01f, "The pier is centred on the joint");
            assertEquals(edge.y, bounds.getCenterY(), .01f);
            // Closed on the joint: from every side, as the side view draws this hex alone, the half shows its outside.
            var inside = new Vector3(edge).mulAdd(toward, -.5f * BoardGeometry.hexScale());
            inside.z = bounds.getCenterZ();
            for (int angle = 0; angle < 360; angle += 30) {
                var look = new Vector3(com.badlogic.gdx.math.MathUtils.cosDeg(angle), com.badlogic.gdx.math.MathUtils.sinDeg(angle), 0);
                var ray = new Ray(new Vector3(inside).mulAdd(look, -200), look);
                BoardBridge.Facet first = null;
                float nearest = Float.POSITIVE_INFINITY;
                var hit = new Vector3();
                for (var facet : halves.get(near)) {
                    if (com.badlogic.gdx.math.Intersector.intersectRayTriangle(ray, facet.a(), facet.b(), facet.c(), hit)
                          && hit.dst2(ray.origin) < nearest) {
                        nearest = hit.dst2(ray.origin);
                        first = facet;
                    }
                }
                assertTrue(first != null && first.normal().dot(look) < 0, near + " looking at " + angle + " degrees sees its outside");
            }
        }

        // Either hex's toggle carries the joint; untoggled, one-hex and ground-level spans and a road beneath have none.
        var either = BridgeScenes.span(2, true, 0, Set.of(1));
        assertEquals(1, joints(BridgeScenes.piers(either, BridgeScenes.START), BridgeScenes.START));
        assertEquals(1, joints(BridgeScenes.piers(either, BridgeScenes.START.translated(3)), BridgeScenes.START.translated(3)));
        assertTrue(BridgeScenes.piers(BridgeScenes.span(3, true, 0, Set.of()), BridgeScenes.START.translated(3)).isEmpty(), "Off");
        assertTrue(BridgeScenes.piers(BridgeScenes.span(1, true, 0, Set.of(0)), BridgeScenes.START).isEmpty(), "A one-hex bridge rests on its landings");
        assertTrue(BridgeScenes.piers(BridgeScenes.span(3, false, 0, Set.of(0, 1, 2)), BridgeScenes.START.translated(3)).isEmpty(), "No room under a ground-level deck");
        var raised = BridgeScenes.span(3, false, 2, Set.of(0, 1, 2));
        assertEquals(2, joints(BridgeScenes.piers(raised, BridgeScenes.START.translated(3)), BridgeScenes.START.translated(3)), "Two levels above dry ground");
        var road = BoardScenes.replace(raised, withRoad(raised.tile(BridgeScenes.START.translated(3)), 9));
        assertTrue(BridgeScenes.piers(road, BridgeScenes.START.translated(3)).isEmpty() && BridgeScenes.piers(road, BridgeScenes.START).isEmpty(), "The road beneath keeps its lane");
        var across = BoardScenes.replace(raised, withRoad(raised.tile(BridgeScenes.START.translated(3)), 18));
        assertEquals(2, joints(BridgeScenes.piers(across, BridgeScenes.START.translated(3)), BridgeScenes.START.translated(3)), "A road crossing beneath, not along");
    }

    @Test
    void aJunctionHasAHalfPierOnEachConnectedEdgeAndItsCentreStaysOpen() {
        Coords centre = new Coords(4, 4);
        Map<Coords, BoardScene.Tile> tiles = new HashMap<>();
        tiles.put(centre, BridgeScenes.pillared(BridgeScenes.deckTile(centre, 1 | 4 | 16, true, 0)));
        for (int d : new int[] { 0, 2, 4 }) {
            Coords at = centre.translated(d);
            tiles.put(at, BridgeScenes.pillared(BridgeScenes.deckTile(at, 1 << (d + 3) % 6, true, 0)));
        }
        var scene = BoardScenes.scene(at -> tiles.getOrDefault(at,
              BoardScenes.tile(at, BoardRoad.Kind.NONE, 0, 0, BoardScene.Surface.GRASS)));
        assertEquals(3, joints(BridgeScenes.piers(scene, centre), centre));
        for (int d : new int[] { 0, 2, 4 }) { assertEquals(1, joints(BridgeScenes.piers(scene, centre.translated(d)), centre.translated(d))); }
    }

    /**
     * A maglev's Pillars: the bridge piers' placement with the slim kit, one pier on each joint between consecutive
     * route hexes (never near a hex centre), each hex building its own half, from below the floor up to the beam,
     * whose underside the cap's shoulders hold, at both level heights.
     */
    @Test
    void maglevPiersStandOnTheJointsOfAToggledRouteUpToItsBeamAtBothLevelHeights() {
        for (int levelHeight : new int[] { BoardGeometry.DEFAULTS.levelHeight(), 30 }) {
            BoardGeometry.tune(new BoardGeometry.Tuning(1, 1, 1, levelHeight, .8f));
            try {
                var scene = BridgeScenes.line(RailRoute.Kind.MAGLEV, new double[] { 2, 2, 2, 2 }, Set.of(0, 1, 2, 3), Set.of());
                List<Integer> counts = new java.util.ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    Coords at = BridgeScenes.START.translated(3, i);
                    var half = maglevPiers(scene, at);
                    counts.add(joints(half, at));
                    for (var facet : half) {
                        for (var p : List.of(facet.a(), facet.b(), facet.c())) {
                            for (var tile : scene.tiles()) {
                                assertTrue(Vector3.dst(p.x, p.y, 0, BoardGeometry.centerX(tile.coords()),
                                      BoardGeometry.centerY(tile.coords()), 0) >= 29.5f, "No pier near a hex centre");
                            }
                            var owner = BoardGeometry.tile(scene, p.x - (p.x - BoardGeometry.centerX(at)) * .001f,
                                  p.y - (p.y - BoardGeometry.centerY(at)) * .001f);
                            assertEquals(at, owner.coords(), "Each hex builds only the half on its own side");
                        }
                    }
                }
                assertEquals(List.of(1, 2, 2, 1), counts, levelHeight + ": one pier on each joint, none at the open ends");
                for (int i = 0; i < 3; i++) {
                    Coords near = BridgeScenes.START.translated(3, i), far = near.translated(3);
                    var edge = BoardGeometry.center(near, 0).lerp(BoardGeometry.center(far, 0), .5f);
                    var bounds = new com.badlogic.gdx.math.collision.BoundingBox().inf();
                    vertices(maglevPiers(scene, near), edge).forEach(bounds::ext);
                    vertices(maglevPiers(scene, far), edge).forEach(bounds::ext);
                    assertEquals(2 * levelHeight + .75f, bounds.max.z, .01f, levelHeight + ": the cap holds the beam's underside");
                    assertEquals(-4, bounds.min.z, .05f, levelHeight + ": the footing is buried in the ground");
                    assertEquals(edge.x, bounds.getCenterX(), .01f, "Centred on the joint");
                    assertEquals(edge.y, bounds.getCenterY(), .01f);
                    assertTrue(bounds.getWidth() <= 7.01f && bounds.getHeight() <= 7.01f, "The slim maglev kit: " + bounds);
                }
            } finally {
                BoardGeometry.tune(BoardGeometry.DEFAULTS);
            }
        }
    }

    /**
     * Where a maglev pier stands: on a climbing beam its cap follows the beam's grade and meets the neighbour's half on
     * the joint; two maglevs crossing at two levels each stand on their own joints; either marker's toggle carries a
     * joint; over water the footing sinks below the bed. None without the toggle, under a beam less than a level up, where
     * a road or ground rail runs beneath along the route or a bridge deck crosses beneath, and never under a rail.
     */
    @Test
    void maglevPiersFollowTheBeamAndKeepClearOfWhatRunsBeneath() {
        var climbing = BridgeScenes.line(RailRoute.Kind.MAGLEV, new double[] { 1, 2, 3, 4 }, Set.of(0, 1, 2, 3), Set.of());
        for (int i = 0; i < 3; i++) {
            Coords near = BridgeScenes.START.translated(3, i), far = near.translated(3);
            var from = BoardRail.joint(climbing, climbing.tile(near), BoardRail.route(climbing.tile(near)), 3);
            var back = BoardRail.joint(climbing, climbing.tile(far), BoardRail.route(climbing.tile(far)), 0);
            assertEquals(from.underside(), back.underside(), .001f, "The beam's underside on the joint, the same from both hexes");
            assertEquals(from.grade(), -back.grade(), .0001f);
            assertTrue(from.grade() > 0, "Climbing towards the neighbour");
            var edge = BoardGeometry.center(near, 0).lerp(BoardGeometry.center(far, 0), .5f);
            var toward = BoardGeometry.center(far, 0).sub(BoardGeometry.center(near, 0)).nor();
            for (Coords at : List.of(near, far)) {
                float top = (float) vertices(maglevPiers(climbing, at), edge).stream()
                      .filter(p -> Math.abs(new Vector3(p).sub(edge).dot(toward)) < .01f).mapToDouble(p -> p.z).max().orElseThrow();
                assertEquals(from.underside() + .75f, top, .02f, at + ": the cap on the joint holds the beam");
            }
        }

        // A ground rail crossing beneath the deck stays on its own layer and does not add elevated joints.
        Coords middle = BridgeScenes.START.translated(3);
        Map<Coords, BoardScene.Tile> tiles = new HashMap<>();
        tiles.put(middle, BridgeScenes.routeTile(middle, false, RailRoute.Kind.MAGLEV, 4, 18, true, 9));
        for (int d : new int[] { 1, 4 }) { tiles.put(middle.translated(d), BridgeScenes.routeTile(middle.translated(d), false,
              RailRoute.Kind.MAGLEV, 4, RailRoute.side((d + 3) % 6), false, 0)); }
        var crossing = BoardScenes.scene(at -> tiles.getOrDefault(at,
              BoardScenes.tile(at, BoardRoad.Kind.NONE, 0, 0, BoardScene.Surface.GRASS)));
        assertEquals(2, joints(maglevPiers(crossing, middle), middle));
        for (int d = 0; d < 6; d++) {
            var edge = BoardGeometry.center(middle, 0).lerp(BoardGeometry.center(middle.translated(d), 0), .5f);
            float top = (float) vertices(maglevPiers(crossing, middle.translated(d)), edge).stream()
                  .mapToDouble(p -> p.z).max().orElse(Double.NaN);
            assertEquals(d % 3 != 1 ? Float.NaN : 4 * BoardGeometry.level() + .75f, top, .01f,
                  "The neighbour's half at side " + d + " (either bridge's toggle carries the joint)");
        }

        // Over water the footing sinks out of sight, its lip (0.6) and half a px below the lowest bed under it.
        Coords second = BridgeScenes.START.translated(3);
        var dry = BridgeScenes.line(RailRoute.Kind.MAGLEV, new double[] { 2, 2, 2 }, Set.of(0, 1, 2), Set.of());
        var wet = BridgeScenes.line(RailRoute.Kind.MAGLEV, new double[] { 2, 2, 2 }, Set.of(0, 1, 2), Set.of(1));
        assertTrue(lowest(maglevPiers(wet, second)) <= lowest(maglevPiers(dry, second)) - 1.1f + .001f,
              lowest(maglevPiers(wet, second)) + " under water, " + lowest(maglevPiers(dry, second)) + " on dry ground");

        assertEquals(0, joints(maglevPiers(BridgeScenes.line(RailRoute.Kind.MAGLEV, new double[] { 2, 2, 2 }, Set.of(), Set.of()), second),
              second), "Off");
        assertEquals(0, joints(maglevPiers(BridgeScenes.line(RailRoute.Kind.MAGLEV, new double[] { 0, 0, 0 }, Set.of(0, 1, 2), Set.of()),
              second), second), "A ground maglev has no bridge piers");
        var rails = BridgeScenes.line(RailRoute.Kind.RAIL, new double[] { 0, 0, 0 }, Set.of(), Set.of());
        assertEquals(0, joints(maglevPiers(rails, second), second), "A rail lies on the ground");
        var along = BoardScenes.replace(dry, withRoad(dry.tile(second), 9));
        assertEquals(0, joints(maglevPiers(along, second), second), "A road beneath along the route keeps its lane");
        var across = BoardScenes.replace(dry, withRoad(dry.tile(second), 18));
        assertEquals(2, joints(maglevPiers(across, second), second), "A road crossing beneath, not along");
        var railBeneath = BoardScenes.replace(dry,
              BridgeScenes.routeTile(second, false, RailRoute.Kind.MAGLEV, 2, 9, true, 9));
        assertEquals(0, joints(maglevPiers(railBeneath, second), second), "A ground rail beneath along the route keeps its track");
    }

    /**
     * Batch-10 answer 8: a maglev pier never pierces a structure: no pier stands on a joint where either hex holds a
     * building or an industrial plant (nor a fuel tank, which is a structure model too); the other joints keep theirs.
     */
    @Test
    void noMaglevPierStandsOnAJointBesideAStructure() {
        var dry = BridgeScenes.line(RailRoute.Kind.MAGLEV, new double[] { 3, 3, 3, 3 }, Set.of(0, 1, 2, 3), Set.of());
        Coords first = BridgeScenes.START, second = BridgeScenes.START.translated(3), third = second.translated(3), fourth = third.translated(3);
        assertEquals(List.of(1, 2, 2, 1), List.of(joints(maglevPiers(dry, first), first), joints(maglevPiers(dry, second), second),
              joints(maglevPiers(dry, third), third), joints(maglevPiers(dry, fourth), fourth)));
        for (var kind : List.of(BoardScene.FeatureKind.BUILDING, BoardScene.FeatureKind.INDUSTRIAL)) {
            var structure = new BoardScene.Feature("buildings/standard/building-light", 0, 0, 0, 1, 2, 0, kind);
            var built = BoardScenes.replace(dry, withFeatures(dry.tile(second), List.of(structure)));
            assertEquals(0, joints(maglevPiers(built, second), second), kind + ": none in the structure's hex");
            assertEquals(0, joints(maglevPiers(built, first), first), kind + ": nor its neighbour's half of that joint");
            assertEquals(1, joints(maglevPiers(built, third), third), kind + ": the joint away from it keeps its pier");
            assertEquals(1, joints(maglevPiers(built, fourth), fourth));
        }
    }

    private static float lowest(List<BoardBridge.Facet> facets) {
        return (float) facets.stream().flatMap(f -> java.util.stream.Stream.of(f.a(), f.b(), f.c())).mapToDouble(p -> p.z)
              .min().orElseThrow();
    }

    /** The hex's maglev pier facets, as the terrain builds them for each of its route markers. */
    static List<BoardBridge.Facet> maglevPiers(BoardScene scene, Coords at) {
        var tile = scene.tile(at);
        return BoardBridge.guideway(tile) ? BridgeScenes.piers(scene, at) : List.of();
    }

    @Test
    void noPierStandsInAChasm() {
        var scene = BridgeScenes.span(3, false, 2, Set.of(0, 1, 2));
        Coords middle = BridgeScenes.START.translated(3);
        assertTrue(BoardBridgeFooting.pier(scene, scene.tile(BridgeScenes.START), 3), "A toggled span two levels up has piers");
        Map<Coords, BoardScene.Tile> tiles = new HashMap<>();
        for (var tile : scene.tiles()) { tiles.put(tile.coords(), tile.coords().equals(middle) ? chasm(tile) : tile); }
        var pit = BoardScenes.scene(tiles::get);
        assertFalse(BoardBridgeFooting.pier(pit, pit.tile(BridgeScenes.START), 3), "Its deck crosses the chasm on no pier");
        assertFalse(BoardBridgeFooting.pier(pit, pit.tile(middle), 3));
    }

    /** The tile as an ultra sublevel, a chasm. */
    private static BoardScene.Tile chasm(BoardScene.Tile t) {
        return new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(), t.roadExits(), t.surface(),
              t.decals(), t.decalsWithoutLimbs(), t.features(), t.text(), t.liquid(), t.tileset(),
              t.detailedGround(), t.road(), t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(), t.cliffTopExits(), t.bare(),
              t.groundCover(), true, t.tilesetDecals(), t.tilesetScenery(), t.appearance());
    }

    /** The tile with these features added. */
    private static BoardScene.Tile withFeatures(BoardScene.Tile t, List<BoardScene.Feature> added) {
        var features = new java.util.ArrayList<>(t.features());
        features.addAll(added);
        return new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(), t.roadExits(), t.surface(),
              t.decals(), t.decalsWithoutLimbs(), List.copyOf(features), t.text(), t.liquid(),
              t.tileset(), t.detailedGround(), t.road(), t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(),
              t.cliffTopExits(), t.bare(), t.groundCover(), t.ultraSublevel(), t.tilesetDecals(),
              t.tilesetScenery(), t.appearance());
    }

    private static BoardScene.Tile withRoad(BoardScene.Tile t, int exits) {
        return new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(), exits, t.surface(),
              t.decals(), t.decalsWithoutLimbs(), t.features(), t.text(), t.liquid(), t.tileset(),
              t.detailedGround(), BoardRoad.Kind.PAVED, t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(), t.cliffTopExits(),
              t.bare(), t.groundCover(), t.ultraSublevel(), t.tilesetDecals(), t.tilesetScenery(), t.appearance());
    }

    /** How many of the hex's edges have pier facets beside their midpoint. */
    private static int joints(List<BoardBridge.Facet> piers, Coords at) {
        int count = 0;
        for (int d = 0; d < 6; d++) {
            var edge = BoardGeometry.center(at, 0).lerp(BoardGeometry.center(at.translated(d), 0), .5f);
            if (!vertices(piers, edge).isEmpty()) { count++; }
        }
        return count;
    }

    /** The pier vertices within a pier's reach of a joint. */
    private static List<Vector3> vertices(List<BoardBridge.Facet> piers, Vector3 edge) {
        return piers.stream().flatMap(f -> java.util.stream.Stream.of(f.a(), f.b(), f.c()))
              .filter(p -> Vector3.dst(p.x, p.y, 0, edge.x, edge.y, 0) < 12 * BoardGeometry.hexScale()).toList();
    }

    @Test
    void asphaltPaintEndsBeforeTheGroundedApronAndTheDeckRemainsSolid() {
        var at = BoardScenes.CENTER;
        var scene = BridgeScenes.canyonScene(BoardScene.Surface.GRASS, false, true);
        scene = BridgeScenes.typed(BoardScenes.replace(scene,
              BoardScenes.tile(at.translated(0), BoardRoad.Kind.PAVED, 9, 3, BoardScene.Surface.GRASS)), HexAppearance.BUILT_BRIDGE);
        scene = paved(scene, at, BoardRoad.Kind.PAVED);
        var tile = scene.tile(at);
        var footing = BoardBridgeFooting.build(scene, tile, TerrainLod.FULL, new HashMap<>());
        float reach = footing.lengths().get(3);
        assertTrue(reach > BoardRelief.metres(1) / BoardGeometry.hexScale(), "The fixture has a receding cliff");
        var deck = BoardBridge.deck(scene, tile);
        var road = footing.road(deck, at);
        var patches = GpuRoads.deckPatches(tile, deck, road, footing);
        var asphalt = patches.stream().filter(p -> p.texture().equals("roads/asphalt")).findFirst().orElseThrow();
        var mask = GpuRoads.mask(road, asphalt);
        assertTrue(mask.y() < -BoardGeometry.TILE_HEIGHT / 2 - reach + 1);
        int painted = 0, gaps = 0;
        var marks = patches.stream().filter(p -> p.texture().equals("concrete-ground")).findFirst().orElseThrow();
        float paintEnd = -BoardGeometry.TILE_HEIGHT / 2 - reach
              + BoardRelief.metres(1.8f) / BoardGeometry.hexScale();
        for (float y = -BoardGeometry.TILE_HEIGHT / 2 - .25f; y > -BoardGeometry.TILE_HEIGHT / 2 - reach + .25f; y -= .25f) {
            assertTrue(asphalt.shape().contains(0, y), "Asphalt covers the whole added passage");
            assertEquals(1, GpuRoads.coverage(road, asphalt, 0, y), "No transparency over the span or solid footing");
            if (marks.shape().contains(0, y)) { painted++; } else { gaps++; }
            if (y < paintEnd) { assertFalse(marks.shape().contains(0, y), "Paint stops before the rail taper"); }
        }
        assertTrue(painted > 0 && gaps > 0, "Real dashed paint continues on the extension");
        var center = BoardGeometry.center(at, 2);
        assertFalse(Float.isFinite(footing.shape().hit(new Ray(new Vector3(center).add(-40, 0, 0), Vector3.X))),
              "The lower crossing is still open");
    }

    @Test
    void bareBankMasksBlendThroughGravelToLocalGroundWithoutFadingConnectedRoads() {
        var at = BoardScenes.CENTER;
        float scale = BoardGeometry.hexScale();
        for (var kind : List.of(BoardRoad.Kind.PAVED, BoardRoad.Kind.ALLEY, BoardRoad.Kind.GRAVEL, BoardRoad.Kind.DIRT)) {
            var scene = BridgeScenes.canyonScene(BoardScene.Surface.GRASS, false, true);
            scene = BridgeScenes.typed(BoardScenes.replace(scene,
                  BoardScenes.tile(at.translated(0), kind, 9, 3, BoardScene.Surface.GRASS)), HexAppearance.BUILT_BRIDGE);
            scene = paved(scene, at, kind);
            var tile = scene.tile(at);
            var surfaces = new HashMap<Coords, BoardSurface>();
            var footing = BoardBridgeFooting.build(scene, tile, TerrainLod.FULL, surfaces);
            assertEquals(8, footing.bareExits(), "The lower road is not mistaken for an approach");
            var deck = BoardBridge.deck(scene, tile);
            var road = footing.road(deck, at);
            var patches = GpuRoads.deckPatches(tile, deck, road, footing);
            String material = GpuRoads.texture(kind);
            // A loose road's one coat is its track, which carries the heading as wheels do.
            var tail = patches.stream().filter(p -> p.texture().equals(material) && p.endFade() > 0
                  && (!p.fade().wheels() || kind == BoardRoad.Kind.DIRT || kind == BoardRoad.Kind.GRAVEL))
                  .findFirst().orElseThrow();
            var mask = GpuRoads.mask(road, tail);
            float start = -BoardGeometry.TILE_HEIGHT / 2 - footing.lengths().get(3);
            float end = start - BoardRelief.metres(7) / scale;
            assertTrue(GpuRoads.coverage(road, tail, 0, start - .05f) > .95f, "The landing starts with the deck's material");
            // The cut between head and tail may fall inside an antialiased mask texel; sample its first full texel.
            assertTrue(maskAlpha(mask, 0, start - .5f) > .95f, "The start of the rasterized landing remains fully covered");
            int partial = 0;
            for (float y = start - .5f; y > end; y -= .25f) {
                float alpha = maskAlpha(mask, 0, y);
                if (alpha > .02f && alpha < .98f) { partial++; }
            }
            assertTrue(partial > 2, "The irregular apron exposes ground over a band, rather than one hard edge: " + kind);
            assertEquals(0, maskAlpha(mask, 0, end - .1f), "No hard edge at the end of the apron");
            if (kind == BoardRoad.Kind.PAVED || kind == BoardRoad.Kind.ALLEY) {
                var gravel = patches.stream().filter(p -> p.texture().equals("roads/gravel")).findFirst().orElseThrow();
                float tip = start - BoardRelief.metres(5) / scale;
                assertEquals(0, maskAlpha(mask, 0, tip), "Asphalt ends before the loose gravel");
                assertTrue(maskAlpha(GpuRoads.mask(road, gravel), 0, tip) > 0, "Gravel carries the final transition");
            }
            assertFalse(GpuRoads.drape(tile, surfaces.get(at.translated(3)), tail).isEmpty(),
                  "Existing bank triangles carry the apron");
            assertTrue(GpuRoads.drape(tile, surfaces.get(at.translated(3)), tail).stream()
                  .allMatch(t -> t.a().z > BoardGeometry.level() * 2 && t.b().z > BoardGeometry.level() * 2
                        && t.c().z > BoardGeometry.level() * 2), "Nothing is painted down onto the underpass");
            float north = BoardGeometry.TILE_HEIGHT / 2 + footing.lengths().get(0) - .25f;
            assertTrue(patches.stream().anyMatch(p -> p.texture().equals(material) && p.shape().contains(0, north)
                  && GpuRoads.coverage(road, p, 0, north) == 1), "Attached roads retain full material coverage");
        }
    }

    /**
     * A collapse keeps its span's landings and piers standing (GpuTerrain#standing, read from the scene before it); the
     * ruin built from the scene after it must be the same triangles, its road and lanes kept, or they change as the
     * ruin replaces them: Fire and Ice 2's paved 1308.
     */
    @Test
    void aFallenSpanIsRebuiltAsItsLandingsStoodThroughTheFall() throws Exception {
        String board = "unofficial/Drewbacca/16x17 Fire And Ice 2.board";
        var span = new Coords(12, 7);
        var before = BoardScenes.installedScene(board);
        // A game converts its board when it loads it; the collapse comes later.
        var after = BoardScenes.installedScene(board, edited -> {
            BoardSceneryLayouts.convert(edited);
            edited.collapseBuilding(span);
        });
        var stood = before.tile(span);
        var ruin = after.tile(span);
        assertTrue(BoardBridge.standing(ruin) == null && BoardBridge.feature(ruin).ruined(), "The span fell");
        assertEquals(BoardRoad.Kind.PAVED, ruin.deckRoad(), "Its landings keep the road that ran onto them");
        assertEquals(stood.deckRoadExits(), ruin.deckRoadExits());
        assertEquals(stood.deckCorridor(), ruin.deckCorridor());
        for (var lod : TerrainLod.values()) {
            var kept = BoardBridgeFooting.build(before, stood, lod, new HashMap<>())
                  .ruin(before, stood, BoardBridge.deck(before, stood), lod, new HashMap<>(), false);
            var rebuilt = BoardBridgeFooting.build(after, ruin, lod, new HashMap<>())
                  .ruin(after, ruin, BoardBridge.deck(after, ruin), lod, new HashMap<>(), false);
            assertFalse(kept.facets().isEmpty(), "Its landings stand");
            assertEquals(triangles(kept), triangles(rebuilt), lod + ": the ruin is what stood");
        }
    }

    /** The shape's triangles as their corners, each to a hundredth of a world unit, with their part. */
    private static Set<String> triangles(BoardBridge.Shape shape) {
        Set<String> result = new java.util.TreeSet<>();
        for (var facet : shape.facets()) {
            var corners = new ArrayList<String>();
            for (var p : List.of(facet.a(), facet.b(), facet.c())) {
                corners.add(Math.round(p.x * 100) + "," + Math.round(p.y * 100) + "," + Math.round(p.z * 100));
            }
            corners.sort(null);
            result.add(facet.part() + ":" + String.join("|", corners));
        }
        return result;
    }

    private static float height(BoardBridge.Shape shape, Vector3 point) {
        var ray = new Ray(new Vector3(point).add(0, 0, 40), new Vector3(0, 0, -1));
        return ray.origin.z - (float) Math.sqrt(shape.hit(ray));
    }

    private static float maskAlpha(GpuRoads.MaskData mask, float x, float y) {
        int px = (int) Math.floor((x - mask.x()) / mask.width() * mask.pixels().width());
        int py = (int) Math.floor((y - mask.y()) / mask.height() * mask.pixels().height());
        if (px < 0 || py < 0 || px >= mask.pixels().width() || py >= mask.pixels().height()) { return 0; }
        return (mask.pixels().rgba(py * mask.pixels().width() + px) & 255) / 255f;
    }
}
