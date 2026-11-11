/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import com.badlogic.gdx.utils.JsonReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardBillboard;
import megamek.common.board.BoardDecalArt;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFlag;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.BoardTreeDistribution;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import megamek.common.board.TilesetScenery;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BoardSceneryTest {
    @Test
    void compositionsReuseMeshesWithoutChangingAuthoredTransformsOrTreeDensity() {
        Path root = Configuration.dataDir().toPath().resolve("models/board");
        var catalog = new JsonReader().parse(new FileHandle(root.resolve("scenery/layouts.json").toFile()));
        int trees = 0;
        int picnicTables = 0;
        var coords = new Coords(7, 9);
        for (var entry : catalog) {
            var layout = BoardSceneryLayouts.layout(entry.name);
            assertEquals(entry.size, layout.components().size());
            // The conversion places the meshes; a car park's lane decal is paint, a route marker's track generated.
            var meshes = layout.components().stream().filter(c -> !c.decal() && !c.route()).toList();
            var natural = layout.chosen(coords, BoardTreeDistribution.PARK, true).stream().filter(c -> !c.decal() && !c.route()).toList();
            var planted = layout.chosen(coords, BoardTreeDistribution.PARK, false).stream().filter(c -> !c.decal() && !c.route()).toList();
            assertEquals(natural, layout.chosen(coords, BoardTreeDistribution.PARK, true).stream().filter(c -> !c.decal() && !c.route()).toList());
            for (int i = 0; i < meshes.size(); i++) {
                var authored = meshes.get(i);
                var component = natural.get(i);
                assertFalse(component.asset().matches("scenery/.*-[0-9a-f]{8}"),
                      "Shared asset identities must not depend on parameter hashes");
                assertEquals(authored.x(), component.x());
                assertEquals(authored.y(), component.y());
                assertEquals(authored.z(), component.z());
                assertEquals(authored.scale(), component.scale());
                assertEquals(authored.asset(), planted.get(i).asset());
                assertEquals(authored.rotation(), planted.get(i).rotation());
                assertTrue(Files.isRegularFile(root.resolve(component.asset() + ".glb")));
                if (authored.tree()) {
                    trees++;
                } else {
                    assertEquals(authored, component, "Only the park trees draw their species and heading");
                    // The remaining furniture meshes cannot carry duplicate copies of the shared trees: where one
                    // has plant cards, they are its flowers and shrubs, and it stands lower than any tree.
                    var data = RigidGlb.loadLods(new FileHandle(root.resolve(component.asset() + ".glb").toFile()), root).getFirst();
                    assertTrue(plantTop(data) < 12, entry.name + " bakes no tree in");
                }
                if (authored.asset().equals("scenery/parks/picnic-table")) { picnicTables++; }
            }
            if (layout.components().stream().noneMatch(c -> c.asset().equals(entry.name))) {
                assertFalse(Files.exists(root.resolve(entry.name + ".glb")), "Compositions need no duplicate mesh");
            }
        }
        // Batch 10: heli pads, elevator plates, cleared-rubble gravel, legacy rails and crossings, train stamps. Batch 12:
        // the ten container yard compositions replace the 76 legacy yard and crane keys.
        assertEquals(315, catalog.size);
        assertEquals(422, trees);
        assertTrue(picnicTables > 10, "The same picnic-table mesh is reused by picnic and courtyard layouts");
    }

    @Test
    void tacticalSceneryKeepsOriginalArtworkSeparateFromTheGroundOnRoofsAndUnderwater() {
        for (String support : List.of("pavement:1", "water:2",
              "pavement:1;building:2;bldg_elev:3;bldg_cf:90")) {
            var bare = capture(new Hex(0, support, ""));
            // Terrain-owned art keeps its sprite; art the conversion takes (fluff, decals) has none.
            for (String decoration : List.of("geyser:1")) {
                var hex = new Hex(0, support + ";" + decoration, "");
                var image = capture(hex);
                assertTrue(alpha(image.tilesetDecals()) + alpha(image.tilesetScenery()) > 0,
                      "Tactical View must retain " + decoration);
                assertEquals(BoardScene.Pixels.capture(bare.tileset(), null), BoardScene.Pixels.capture(image.tileset(), null),
                      "The ground must not retain a duplicate of " + decoration);
                var tile = tile(hex, image);
                assertEquals(tile.tilesetDecals(), tile.withText(List.of()).tilesetDecals(),
                      "Label refreshes must retain painted artwork");
                assertEquals(tile.tilesetScenery(), tile.withText(List.of()).tilesetScenery(),
                      "Label refreshes must retain object artwork");
            }
            var skylight = drawn(support + ";fluff:6:0");
            assertTrue(skylight.hex().containsTerrain(Terrains.FLUFF), "The token stays for the 2D board");
            assertNull(skylight.tile().tilesetScenery(), "The Tactical View shows the skylight object, not its sprite");
            assertNull(skylight.tile().tilesetDecals());
            assertEquals(BoardScene.Pixels.capture(bare.tileset(), null), skylight.tile().tileset(),
                  "The Tactical View's base tile has no copy of it either");
        }
    }

    @Test
    void rubbleUsesTheDestroyedStructureTypeAndClearedPathsStayCosmetic() {
        var families = List.of("light", "medium", "heavy", "hardened", "wall", "heavy");
        for (int type = 1; type <= 6; type++) {
            var rubble = new Hex(0, "rubble:" + type, "");
            var captured = capture(rubble);
            assertEquals(List.of("scenery/saxarba/misc/rubble_" + families.get(type - 1)), captured.scenery().models());
            assertEquals(type, rubble.terrainLevel(Terrains.RUBBLE));
            assertEquals(0, alpha(captured.decals()), "The modeled pile replaces its painted duplicate");
            if (type > 5) { continue; }
            var cleared = new Hex(0, "ground_fluff:2000;fluff:" + (2000 + type), "");
            var path = capture(cleared);
            // The cleared ground's gravel (a terrain-owned decal row) under the path's debris.
            assertEquals(List.of("scenery/saxarba/cleared_rubble_gravel", "scenery/saxarba/rubble_" + families.get(type - 1)
                  + "_path"), path.scenery().models());
            assertFalse(cleared.containsTerrain(Terrains.RUBBLE), "Cosmetic debris must not restore the obstacle");
        }
        assertEquals(List.of("scenery/saxarba/misc/fortified"), capture(new Hex(0, "fortified:1", "")).scenery().models());
    }

    @Test
    void everyShippedSceneryAssetDecodesThroughTheRuntimeIncludingItsSharedTextures() throws Exception {
        Path root = Configuration.dataDir().toPath().resolve("models/board");
        try (var files = Files.walk(root.resolve("scenery"))) {
            var models = files.filter(p -> p.toString().endsWith(".glb")).toList();
            int compositions = 0;
            for (var entry : new JsonReader().parse(new FileHandle(root.resolve("scenery/layouts.json").toFile()))) {
                if (!Files.exists(root.resolve(entry.name + ".glb"))) { compositions++; }
            }
            assertTrue(models.size() + compositions >= 281, "The full model and composition catalog is shipped");
            for (Path file : models) {
                assertFalse(file.getFileName().toString().matches(".*-[0-9a-f]{8}\\.glb"),
                      "No obsolete hashed component stays in the asset pool");
                var data = RigidGlb.loadLods(new FileHandle(file.toFile()), root).getFirst();
                assertFalse(data.meshes.isEmpty(), file.toString());
            }
        }
    }

    @Test
    void nativeRoadBendsAndGroundTransitionsStayNativeBesideScenery() {
        var hex = new Hex(0, "road:1:5;road_fluff:1;ground_fluff:1:3;fluff:6:0", "");
        var image = capture(hex);
        assertEquals(0, image.scenery().cosmeticRoadExits());
        assertEquals(0, alpha(image.decals()));
        assertTrue(tile(hex, image).detailedGround());
    }
    @Test
    void fccwCraneSkylightsAndLedgesKeepPhysicalGroundAndReplaceTheirPaintedObjects() {
        for (String terrain : List.of("fluff:7:0", "fluff:6:0", "fluff:8:0", "fluff:2:3")) {
            var drawn = drawn("pavement:1;" + terrain);
            assertFalse(drawn.hex().getDecorations().isEmpty(), terrain);
            assertTrue(drawn.tile().detailedGround(), terrain);
            assertTrue(drawn.tile().features().stream().anyMatch(f -> f.decoration() != null), terrain);
            assertTrue(drawn.tile().features().stream().noneMatch(f -> f.kind() == BoardScene.FeatureKind.SCENERY), terrain);
            assertNull(drawn.tile().decals(), "A modeled object must not leave its painted twin: " + terrain);
            assertNotNull(drawn.tile().tileset(), "Tactical artwork remains available");
        }
    }

    @Test
    void everySkylightAndLedgeRotationUsesTheSelectedSourceVariant() {
        for (int variant = 0; variant < 6; variant++) {
            for (String family : List.of("6", "8")) {
                var hex = new Hex(0, "fluff:" + family + ":" + variant, "");
                assertEquals(List.of("scenery/fluff/" + (family.equals("6") ? "skylight" : "ledge")
                      + (variant + 1)), TilesetScenery.resolve(hex, false).scenery().models());
            }
        }
    }

    @Test
    void everyExportedLedgeFacesOutwardAndHasAVisibleUpperSurface() {
        Path root = Configuration.dataDir().toPath().resolve("models/board");
        // All six legacy ledge orientations are rows on this one mesh.
        var file = root.resolve("scenery/roofs/ledge.glb");
        var data = RigidGlb.loadLods(new FileHandle(file.toFile()), root).getFirst();
        double volume = 0;
        double upperArea = 0;
        double footprintArea = 0;
        for (var mesh : data.meshes) {
            for (var part : mesh.parts) {
                for (int i = 0; i < part.indices.length; i += 3) {
                    var points = new Vector3[3];
                    for (int p = 0; p < 3; p++) {
                        int offset = Short.toUnsignedInt(part.indices[i + p]) * RigidGlb.STRIDE;
                        points[p] = new Vector3(mesh.vertices[offset], mesh.vertices[offset + 1], mesh.vertices[offset + 2]);
                    }
                    var normal = points[1].cpy().sub(points[0]).crs(points[2].cpy().sub(points[0]));
                    volume += points[0].dot(normal) / 6.0;
                    if (points[0].z + points[1].z + points[2].z > .001f) {
                        upperArea += Math.max(0, normal.z) / 2.0;
                    } else {
                        footprintArea += Math.abs(normal.z) / 2.0;
                    }
                }
            }
        }
        // Inward winding previously hid the parapet and left only its narrow trim visible.
        assertTrue(volume > 0, "The closed ledge must enclose positive volume: " + file);
        assertTrue(footprintArea > 0 && upperArea >= footprintArea * .99,
              "The upper faces must cover the body footprint when backface culling is enabled: " + file);
    }

    @Test
    void surfaceSymbolsRemainDecalsWithoutSwitchingOffTheTerrainMaterial() {
        for (String terrain : List.of("fluff:14:0", "fluff:50:1", "fluff:70:1", "fluff:91:1")) {
            var hex = new Hex(0, "pavement:1;" + terrain, "");
            var image = capture(hex);
            assertTrue(image.scenery().models().isEmpty(), terrain);
            // A surface symbol is a legacy decal: the conversion turns it into a decal both views draw.
            assertFalse(image.scenery().decals().isEmpty(), terrain);
            assertTrue(tile(hex, image).detailedGround(), terrain);
        }
    }

    @Test
    void compositePortArtReplacesVisualTreesButRetainsTheGamesWoodsTerrain() {
        var drawn = drawn("pavement:1;woods:1;fluff:80:1");
        assertEquals(1, drawn.hex().terrainLevel(Terrains.WOODS));
        assertEquals("woods/container-yard/rows", drawn.hex().getAppearance().get("vegetation").variant());
        assertTrue(BoardTreeDistribution.noTrees(drawn.hex()));
        assertFalse(drawn.tile().features().stream().anyMatch(f -> f.kind() == BoardScene.FeatureKind.TREE));
        // The yard is the skin's rows, which the woods own: drawn as objects, never stored, never picked.
        assertTrue(drawn.hex().getDecorations().isEmpty());
        assertEquals(List.of("scenery/seaport/yard/light-rows", "scenery/seaport/yard/yard-truck"), drawn.tile().features().stream()
              .filter(f -> f.decoration() != null).map(f -> f.decoration().asset()).toList());
        assertTrue(drawn.tile().features().stream().filter(f -> f.decoration() != null)
              .allMatch(f -> BoardFeatures.terrainOwned(f.decoration())));
        // The Tactical View's base tile follows the same design (b9-decisions T): no woods sprite under the yard, as the
        // old tileset's seaport super replaced it; plain woods keep theirs.
        assertEquals(drawn("pavement:1").tile().tileset(), drawn.tile().tileset());
        assertNotEquals(drawn("pavement:1;woods:1").tile().tileset(), drawn.tile().tileset());
    }

    @Test
    void roadsideTreesAndParkedCarsPreserveTheRoadAndAllSourceVariants() {
        for (String terrain : List.of("road:2:9", "road:1:9;road_fluff:3", "road:1:9;fluff:5:2")) {
            var drawn = drawn(terrain);
            assertFalse(drawn.hex().getDecorations().isEmpty(), terrain);
            assertNull(drawn.tile().decals(), terrain);
            assertEquals(9, drawn.tile().roadExits(), terrain);
            assertTrue(drawn.tile().road() != BoardRoad.Kind.NONE, terrain);
            assertTrue(drawn.tile().detailedGround(), terrain);
        }
    }

    @Test
    void everyParkingLaneIsARealRoadThroughItsBays() {
        int[] exits = { 18, 36, 9, 18, 36, 9, 36, 9 };
        for (int variant = 0; variant < exits.length; variant++) {
            var drawn = drawn("fluff:5:" + variant);
            var tile = drawn.tile();
            assertEquals(exits[variant], drawn.hex().getTerrain(Terrains.ROAD).getExits(), "The lane is rules terrain");
            assertEquals(BoardRoad.Kind.PAVED, tile.road());
            assertEquals(exits[variant], tile.roadExits());
            assertNull(tile.decals());
            assertTrue(BoardRoad.rendered(tile));
            assertTrue(tile.detailedGround());
            // One standard car-park decal per side of the lane with cars, its aisle (along the decal's width) through
            // the same exits; the other side is turned 180 degrees (sized for its own cars).
            var lanes = lanes(drawn.legacy());
            assertTrue(lanes.size() == 1 || lanes.size() == 2
                  && Math.floorMod(Math.round(lanes.get(0).rotation() - lanes.get(1).rotation()), 360) == 180, "Variant " + variant);
            for (var lane : lanes) {
                assertTrue(lane.asset().matches("decal/car-park/(straight|parallel)-\\d+"), lane.asset());
                for (int direction = 0; direction < 6; direction++) {
                    if ((exits[variant] & 1 << direction) != 0) {
                        assertEquals(0, Math.floorMod(Math.round(lane.rotation()) + 60 * direction + 90, 180),
                              "The aisle turns to exit " + direction);
                    }
                }
            }
            assertTrue(tile.features().stream().anyMatch(f -> f.decoration() != null
                  && f.decoration().asset().startsWith("decal/")), "The bays are decal objects");
        }
        for (int variant : new int[] { 98, 99 }) {
            var drawn = drawn("fluff:5:" + variant);
            assertEquals(BoardRoad.Kind.NONE, drawn.tile().road(), "Furniture-only variants have no lane");
            assertEquals(0, drawn.tile().roadExits());
            assertFalse(lanes(drawn.legacy()).isEmpty(), "Their parked cars still get their bays");
        }
    }

    @Test
    void horizontalAndLegacyDirtRoadFluffUsesNativeRoadMaterials() {
        var bare = BoardScene.Pixels.capture(capture(new Hex(0)).tileset(), null);
        for (String terrain : List.of("road:1:9;road_fluff:2", "road:3:9;road_fluff:2", "road:3:9;road_fluff:100")) {
            var hex = new Hex(0, terrain, "");
            var image = capture(hex);
            var tile = tile(hex, image);
            assertEquals(0, alpha(image.decals()), terrain);
            assertEquals(0, alpha(image.tilesetDecals()), "Classic uses the shared road material: " + terrain);
            assertEquals(bare, BoardScene.Pixels.capture(image.tileset(), null), "No duplicate road in the base artwork");
            assertEquals(BoardRoad.capture(hex), tile.road(), terrain);
            assertEquals(9, tile.roadExits());
            assertTrue(BoardRoad.rendered(tile), terrain);
        }
        for (String design : List.of("road/rounded", "road/horizontal", "road/legacy-dirt", "road/material/asphalt")) {
            var hex = new Hex(0, "road:1:27", "");
            hex.setAppearance(java.util.Map.of("road", new megamek.common.board.HexAppearance(design, null, null, null)));
            var image = capture(hex);
            assertEquals(bare, BoardScene.Pixels.capture(image.tileset(), null), design);
            assertEquals(0, alpha(image.tilesetDecals()), design);
        }
    }

    @Test
    void gardenLakesKeepTheirBoundedWaterWithoutChangingGameDepth() {
        // Garden lakes are plain FLUFF (the shipped template and tileset no longer pair them with WATER:0).
        for (int variant = 1; variant <= 5; variant++) {
            var drawn = drawn("fluff:94:" + variant);
            assertEquals(List.of("scenery/saxarba/SMV_Fluff/FluffSystem-07-Garden-04-Lake-1-0" + variant),
                  TilesetScenery.resolve(drawn.legacy(), false).scenery().models());
            assertFalse(drawn.tile().liquid().present(), "The object provides the pond's water inside its banks");
            assertFalse(drawn.tile().water());
        }
        // Game water under lake art is game water too: no artwork turns a WATER token into a decorative basin.
        for (String terrain : List.of("water:0", "water:0;fluff:94:1", "water:1;fluff:94:1", "water:0;ice:1;fluff:94:1")) {
            assertTrue(drawn(terrain).tile().liquid().present(), "Ordinary game water keeps the water engine");
        }
    }

    @Test
    void roofFurnitureDecodesToOneObjectOnTheRoof() {
        // Every legacy key decodes through rows, the glass dome too: the object stands on the roof.
        var drawn = drawn("building:2;bldg_elev:3;bldg_cf:50;fluff:90:9");
        var dome = drawn.hex().getDecorations().stream().filter(o -> o.asset().equals("scenery/roofs/glass-dome")).toList();
        assertEquals(1, dome.size());
        assertEquals("building", dome.getFirst().placement().receiver().terrain());
        assertTrue(drawn.tile().features().stream().anyMatch(f -> dome.getFirst().equals(f.decoration())));
        assertTrue(drawn.tile().features().stream().noneMatch(f -> f.kind() == BoardScene.FeatureKind.SCENERY));
    }

    private static List<BoardSceneryLayouts.Component> lanes(Hex legacy) {
        return BoardSceneryLayouts.layout(TilesetScenery.resolve(legacy, false).scenery().models().getFirst()).components()
              .stream().filter(BoardSceneryLayouts.Component::decal).toList();
    }

    /** A legacy hex, the same hex converted (its token kept) and the tile every 3D view draws for it. */
    private record Drawn(Hex legacy, Hex hex, BoardScene.Tile tile) { }

    private static Drawn drawn(String terrain) {
        Board board = Board.createEmptyBoard(1, 1);
        board.setHex(new Coords(0, 0), new Hex(0, terrain, ""));
        Hex legacy = board.getHex(0, 0);
        BoardSceneryLayouts.convert(board);
        return new Drawn(legacy, board.getHex(0, 0), BoardTiles.captureAll(board).getFirst());
    }

    private static BoardArtwork.HexImage capture(Hex hex) {
        Board board = Board.createEmptyBoard(1, 1);
        board.setHex(new Coords(0, 0), hex);
        try (var artwork = new BoardArtwork()) { return artwork.capture(board, new Coords(0, 0), board.getHex(new Coords(0, 0))); }
    }

    private static BoardScene.Tile tile(Hex hex, BoardArtwork.HexImage image) {
        return BoardScene.captureTile(hex, image, null, new BoardScene.PixelPool(), neighbour -> null);
    }

    private static int alpha(java.awt.image.BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) { count++; }
            }
        }
        return count;
    }

    static final List<String> VARIANTS = List.of("1", "2", "3", "4", "5", "6", "7", "8", "2b", "3b");

    static final List<Integer> COUNTS = List.of(10, 6, 10, 11, 7, 6, 5, 6, 5, 6);

    @Test
    void everyParkingSpriteKeepsItsCarsAndNeutralBodiesMatchTheOriginalPixels() throws Exception {
        var root = Configuration.dataDir().toPath().resolve("models/board");
        int neutralCars = 0;
        for (int index = 0; index < VARIANTS.size(); index++) {
            String variant = VARIANTS.get(index);
            String name = "scenery/fluff/cars_" + variant;
            var layout = BoardSceneryLayouts.layout(name);
            var cars = layout.components().stream().filter(c -> c.asset().startsWith("scenery/vehicles/car")).toList();
            assertEquals(COUNTS.get(index).intValue(), cars.size(), variant);
            assertFalse(Files.exists(root.resolve(name + ".glb")), "No duplicate car geometry stays in the layout");
            BufferedImage reference = ImageIO.read(new File(Configuration.hexesDir(), "fluff/cars_" + variant + ".gif"));
            for (var car : cars) {
                assertFalse(car.tree());
                var data = RigidGlb.loadLods(new FileHandle(root.resolve(car.asset() + ".glb").toFile()), root).getFirst();
                // The row's paint replaces the shared car's paint slot, as the renderer replaces it.
                RigidGlb.recolour(data, car.colours());
                float[] vertices = data.meshes.first().vertices;
                float red = vertices[6], green = vertices[7], blue = vertices[8];
                if (Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) > .01f) { continue; }
                neutralCars++;
                int bodyPixels = 0;
                double angle = Math.toRadians(car.rotation());
                for (int x = -1; x <= 1; x++) for (int y = -2; y <= 2; y++) {
                    int px = Math.round(42 + car.x() + (float) (x * Math.cos(angle) - y * Math.sin(angle)));
                    int py = Math.round(36 - car.y() - (float) (x * Math.sin(angle) + y * Math.cos(angle)));
                    int pixel = reference.getRGB(px, py);
                    int r = pixel >> 16 & 255, g = pixel >> 8 & 255, b = pixel & 255;
                    if ((pixel >>> 24) > 128 && Math.min(r, Math.min(g, b)) > 45
                          && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 5) { bodyPixels++; }
                }
                assertTrue(bodyPixels >= 7, "Neutral car must overlap its body, not blue-gray curb paint: "
                      + variant + " at " + car.x() + "," + car.y() + " / " + bodyPixels);
            }
            var coords = new Coords(2, 2);
            assertEquals(layout.chosen(coords, BoardTreeDistribution.PARK, false), layout.chosen(coords, BoardTreeDistribution.PARK, true),
                  "The tree distribution cannot randomize parked cars");
        }
        assertEquals(16, neutralCars);
        // The road-free 2b/3b sprites are 7/8 without the lane.
        assertEquals(objects("scenery/fluff/cars_7"), objects("scenery/fluff/cars_2b"));
        assertEquals(objects("scenery/fluff/cars_8"), objects("scenery/fluff/cars_3b"));
    }

    private static List<BoardSceneryLayouts.Component> objects(String layout) {
        return BoardSceneryLayouts.layout(layout).components().stream().filter(c -> !c.decal()).toList();
    }

    /** The top of a model's plant cards (its cutout materials' parts); 0 without any. */
    private static float plantTop(com.badlogic.gdx.graphics.g3d.model.data.ModelData data) {
        java.util.Set<String> cards = new java.util.HashSet<>();
        var open = new java.util.ArrayDeque<com.badlogic.gdx.graphics.g3d.model.data.ModelNode>();
        data.nodes.forEach(open::add);
        while (!open.isEmpty()) {
            var node = open.pop();
            if (node.children != null) { open.addAll(List.of(node.children)); }
            if (node.parts == null) { continue; }
            for (var part : node.parts) { if (part.materialId.endsWith("-cutout")) { cards.add(part.meshPartId); } }
        }
        float top = 0;
        for (var mesh : data.meshes) {
            for (var part : mesh.parts) {
                if (!cards.contains(part.id)) { continue; }
                for (short index : part.indices) { top = Math.max(top, mesh.vertices[(index & 0xFFFF) * RigidGlb.STRIDE + 2]); }
            }
        }
        return top;
    }

    @Test
    void parkingBarrierIsARowOfLowStripedBlocksAlongItsHexSide() {
        var data = load("scenery/vehicles/parking-barrier");
        // Its own picture: concrete and hazard stripes.
        assertEquals(1, data.materials.size);
        int triangles = 0, ground = 0;
        for (var mesh : data.meshes) {
            for (var part : mesh.parts) { triangles += part.indices.length / 3; }
            float[] values = mesh.vertices;
            for (int i = 0; i < values.length; i += RigidGlb.STRIDE) {
                float x = Math.abs(values[i] - 24), y = values[i + 1], z = values[i + 2];
                assertTrue(x <= 1.4001f && Math.abs(y) <= 12.5001f && z >= 0 && z <= 2.1001f);
                if (z == 0) { ground++; }
            }
        }
        assertTrue(triangles <= 80 && ground >= 4);
    }

    @Test
    void suburbsUseSharedFurnitureAndLeaveBiomeGroundUncovered() {
        var root = Configuration.dataDir().toPath().resolve("models/board");
        for (int variant = 1; variant <= 3; variant++) {
            String name = "scenery/fluff/suburb" + variant;
            var layout = BoardSceneryLayouts.layout(name);
            assertFalse(Files.exists(root.resolve(name + ".glb")), "No residual slab remains");
            for (var component : layout.components()) {
                assertFalse(component.asset().equals(name));
                if (component.tree()) { continue; }
                float lowFloorArea = 0;
                for (var mesh : load(component.asset()).meshes) {
                    float[] values = mesh.vertices;
                    for (var part : mesh.parts) {
                        for (int i = 0; i < part.indices.length; i += 3) {
                            int a = (part.indices[i] & 0xFFFF) * RigidGlb.STRIDE;
                            int b = (part.indices[i + 1] & 0xFFFF) * RigidGlb.STRIDE;
                            int c = (part.indices[i + 2] & 0xFFFF) * RigidGlb.STRIDE;
                            if (values[a + 5] < .99f || Math.max(values[a + 2],
                                  Math.max(values[b + 2], values[c + 2])) > .61f) { continue; }
                            lowFloorArea += Math.abs((values[b] - values[a]) * (values[c + 1] - values[a + 1])
                                  - (values[b + 1] - values[a + 1]) * (values[c] - values[a])) / 2;
                        }
                    }
                }
                assertTrue(lowFloorArea * component.scale() * component.scale() < 40,
                      "Only small structural feet/steps may cover the ground: " + component.asset());
            }
        }
        var grandstand = BoardSceneryLayouts.layout("scenery/fluff/suburb1").components().stream()
              .filter(c -> c.asset().equals("scenery/parks/grandstand")).toList();
        assertEquals(1, grandstand.size());
        assertTrue(load(grandstand.getFirst().asset()).meshes.first().parts[0].indices.length / 3 <= 400);
        var courtyard = BoardSceneryLayouts.layout("scenery/fluff/suburb2").components();
        assertEquals(1, courtyard.stream().filter(c -> c.asset().equals("scenery/parks/picnic-table")).count());
        var pipes = BoardSceneryLayouts.layout("scenery/fluff/suburb3").components().stream()
              .filter(c -> c.asset().equals("scenery/construction/concrete-pipe")).toList();
        assertEquals(3, pipes.size());
        for (int i = 0; i < pipes.size(); i++) {
            assertEquals(10 + i * 4, pipes.get(i).x());
            assertEquals(-7 + i * 2, pipes.get(i).y());
            assertEquals(0, pipes.get(i).z());
        }
    }

    private static ModelData load(String asset) {
        var root = Configuration.dataDir().toPath().resolve("models/board");
        return RigidGlb.loadLods(new FileHandle(root.resolve(asset + ".glb").toFile()), root).getFirst();
    }

    /** A route marker's track is generated by the route builder, never a model file. */
    private static boolean generated(String asset) {
        return RailRoute.Kind.of(asset) != null || asset.equals(BoardFlag.ASSET) || asset.equals(BoardBillboard.ASSET);
    }

    /** Terrain-owned rows: rubble, gravel and plates, and a vegetation skin's composition ({@code yard/}). */
    private static boolean undecoded(String key) {
        return key.matches("scenery/saxarba/(misc/|rubble_|cleared_rubble_gravel).*") || key.startsWith("yard/");
    }

    @Test
    void decodeRowsAndThePaletteAgree() throws Exception {
        Path root = Configuration.dataDir().toPath().resolve("models/board");
        JsonNode layouts = new ObjectMapper().readTree(root.resolve("scenery/layouts.json").toFile());
        JsonNode decals = new ObjectMapper().readTree(root.resolve("decals/decals.json").toFile());
        BoardEditorBlueprint blueprint = BoardEditorBlueprint.get();
        for (var keys = layouts.fieldNames(); keys.hasNext(); ) {
            String key = keys.next();
            if (undecoded(key)) {
                for (JsonNode row : layouts.get(key)) {
                    String asset = row.get("asset").asText();
                    assertTrue(asset.startsWith("decal/") ? BoardDecalArt.image(asset).isFile()
                          && decals.get("decals").has(asset) : Files.isRegularFile(root.resolve(asset + ".glb")),
                          "Terrain-owned art exists: " + key + " draws " + asset);
                }
                continue;
            }
            for (JsonNode row : layouts.get(key)) {
                String asset = row.get("asset").asText();
                var entry = blueprint.asset(asset);
                assertTrue(entry != null, "Imported objects keep a label: " + asset);
                assertTrue(entry.kind().equals("decal") ? BoardDecalArt.image(asset).isFile()
                      : generated(asset) || Files.isRegularFile(root.resolve(asset + ".glb")), asset);
                if (row.has("seat")) {
                    var plates = new java.util.ArrayList<String>();
                    layouts.get(key).forEach(other -> { if (other.get("asset").asText().startsWith("decal/")) { plates.add(other.get("asset").asText()); } });
                    assertTrue(row.get("seat").get(1).asInt() < BoardDecalArt.footprint(plates.get(row.get("seat").get(0).asInt())).slots().size(),
                          "A seat names a slot of one of the key's decal rows: " + key);
                }
                assertEquals(0, row.get("position").get(2).asDouble(), "Rows stand on their receiver: " + key);
                if (row.has("stretch")) {
                    var stretch = row.get("stretch");
                    assertTrue(stretch.size() == 3 && stretch.get(0).asDouble() > 0 && stretch.get(1).asDouble() > 0
                          && stretch.get(2).asDouble() > 0, "Stretch is three positive factors: " + key);
                }
                if (row.has("colours")) {
                    assertTrue(row.get("colours").size() <= RigidGlb.colourSlots(root.resolve(asset + ".glb").toFile()).size(),
                          "A row recolours only its mesh's slots: " + key);
                }
            }
        }
        // Every cover of every skin names a terrain-owned composition (its rows' art exists, above).
        for (var variant : blueprint.variants("vegetation")) {
            if (variant.skin() == null) { continue; }
            variant.skin().layouts().forEach((cover, key) -> assertTrue(undecoded(key) && layouts.has(key), variant.id() + " " + cover));
        }
        // A legacy decal id decodes to a decal with an image; a one-hex variant shrinks it, a set piece shows it whole.
        for (var ids = decals.get("legacy").fieldNames(); ids.hasNext(); ) {
            var legacy = BoardDecalArt.legacy(ids.next());
            assertTrue(BoardDecalArt.image(legacy.decal()).isFile() && decals.get("decals").has(legacy.decal()), legacy.decal());
            assertTrue(legacy.set() ? legacy.scale() == 1 && legacy.rotation() == 0 : legacy.direction() == -1 && legacy.scale() <= 1,
                  legacy.toString());
        }
        for (var asset : blueprint.assets()) {
            assertFalse(layouts.has(asset.id()), "A palette id is never a decode key: " + asset.id());
            if (asset.layout() != null) {
                assertEquals("prop", asset.kind());
                for (int exits = 0; exits < (asset.layout().contains("{exits}") ? 64 : 1); exits++) {
                    String key = asset.layout().replace("{exits}", String.format(Locale.ROOT, "%02d", exits));
                    assertTrue(layouts.has(key), asset.id() + " stamps " + key);
                }
                // A train stamp (freight, express and colony trains, city and colony maglevs) rides one kind
                // of route with every member, so a stamp on a track snaps the whole train onto it.
                if (asset.id().endsWith("-train")) {
                    var rides = new java.util.HashSet<RailRoute.Kind>();
                    for (JsonNode row : layouts.get(asset.layout())) {
                        String member = row.get("asset").asText();
                        assertTrue(RailRoute.vehicle(member), asset.id() + ": " + member + " is a vehicle");
                        rides.add(RailRoute.rides(member));
                    }
                    assertEquals(1, rides.size(), asset.id() + " rides one route kind: " + rides);
                }
            }
            if (asset.layout() == null && asset.kind().equals("prop") && !generated(asset.id())) {
                var model = (asset.id().startsWith("buildings/") ? root.getParent() : root).resolve(asset.id() + ".glb").toFile();
                assertTrue(model.isFile(), asset.id() + " has its model");
            }
            // A tree takes its winter form on snow, so the -snow trees stay out of the palette.
            if (!asset.sections().isEmpty() && asset.kind().equals("prop")) { assertFalse(asset.id().endsWith("-snow"), asset.id()); }
            if (asset.kind().equals("decal")) {
                assertTrue(BoardDecalArt.image(asset.id()).isFile() && decals.get("decals").has(asset.id()), asset.id());
            }
        }
        // Layers type icons name components by id and objects by palette section or category: a renamed one loses its icon.
        var icons = GpuBoardEditor.class.getDeclaredField("LAYER_ICONS");
        icons.setAccessible(true);
        for (Object key : ((Map<?, ?>) icons.get(null)).keySet()) {
            assertTrue(blueprint.components().stream().anyMatch(c -> c.id().equals(key))
                  || blueprint.categories().stream().anyMatch(c -> c.id().equals(key)
                        || c.sections().stream().anyMatch(section -> section.path().equals(key))),
                  "Layers icon key " + key + " is a component id, a palette category or a section path");
        }
    }

    @BeforeAll
    static void loadNatives() {
        GdxNativesLoader.load();
    }
}
