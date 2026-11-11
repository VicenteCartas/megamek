/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.BoardTreeDistribution;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.HexTile;
import megamek.common.board.TilesetScenery;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/**
 * The legacy conversion of scenery tokens into objects and groups, against hand-checked decode rows; the tokens stay
 * on the board for the 2D board.
 */
class BoardLegacyImportTest {
    private static final String PICNIC = "fluff:93:8";
    private static final Coords TABLES = new Coords(0, 0), ROOF = new Coords(1, 0), BRIDGE = new Coords(2, 0),
          ROAD = new Coords(3, 0), MAGLEV = new Coords(4, 0), SEAPORT = new Coords(5, 0), INVALID = new Coords(6, 0),
          ICE = new Coords(7, 0), TANK = new Coords(8, 0);

    private static Board fixture() {
        Board board = Board.createEmptyBoard(9, 1);
        board.setHex(TABLES, new Hex(0, PICNIC, ""));
        board.setHex(ROOF, new Hex(0, "building:2;bldg_elev:2;bldg_cf:50;fluff:6:14", ""));
        board.setHex(BRIDGE, new Hex(0, "water:2;bridge:1:09;bridge_cf:40;bridge_elev:1;fluff:4:06", ""));
        board.setHex(ROAD, new Hex(0, "road:2:09", ""));
        board.setHex(MAGLEV, new Hex(0, "fluff:9:00", ""));
        board.setHex(SEAPORT, new Hex(0, "woods:1;foliage_elev:2;fluff:80:01", ""));
        board.setHex(INVALID, new Hex(0, "fluff:9:36", ""));
        board.setHex(ICE, new Hex(0, "water:2;ice:1;fluff:8:00", ""));
        board.setHex(TANK, new Hex(0, "fuel_tank:1;fuel_tank_elev:2;fuel_tank_cf:50;fuel_tank_magn:100;fluff:2:03", ""));
        return board;
    }

    @Test
    void pillarArtOnABridgeIsTheBridgesToggleAndTheConvertedBoardDrawsNoPosts() throws Exception {
        // An Oanhu span over water between two roads (pillars5 on SE-NW decks), a raised dry deck, a deck under ice,
        // a deck at ground level (Sirius V), pillars on plain ground, a road with trees passing under a deck (Koryo)
        // and a car park on a deck.
        Coords west = new Coords(1, 1), east = west.translated(2), raised = new Coords(5, 1), iced = new Coords(6, 1),
              level = new Coords(7, 1), plain = new Coords(8, 1), trees = new Coords(9, 1), parked = new Coords(10, 1);
        Map<Coords, String> terrain = new java.util.LinkedHashMap<>();
        terrain.put(west.translated(5), "road:1:36");
        terrain.put(west, "water:1;bridge:1:36;bridge_cf:40;bridge_elev:0;fluff:4:10");
        terrain.put(east, "water:1;bridge:1:36;bridge_cf:40;bridge_elev:0;fluff:4:10");
        terrain.put(east.translated(2), "road:1:36");
        terrain.put(raised, "bridge:1:09;bridge_cf:40;bridge_elev:2;fluff:4:06");
        terrain.put(iced, "water:2;ice:1;bridge:1:09;bridge_cf:40;bridge_elev:1;fluff:4:06");
        terrain.put(level, "bridge:1:09;bridge_cf:40;bridge_elev:0;fluff:4:06");
        terrain.put(plain, "fluff:4:06");
        terrain.put(trees, "bridge:1:40;bridge_cf:40;road:2:18;bridge_elev:4");
        terrain.put(parked, "bridge:1:09;bridge_cf:40;bridge_elev:2;fluff:5:02");
        Board board = Board.createEmptyBoard(12, 4);
        terrain.forEach((at, text) -> board.setHex(at, new Hex(0, text, "")));
        BoardSceneryLayouts.convert(board);

        List<Coords> pillared = List.of(west, east, raised, iced, level);
        for (Coords at : pillared) {
            Hex hex = board.getHex(at);
            assertTrue(HexAppearance.pillars(hex.getAppearance()), at + ": the toggle is on");
            assertTrue(hex.containsTerrain(Terrains.FLUFF), at + ": the decoded token stays for the 2D board");
            assertTrue(hex.getDecorations().isEmpty(), at + ": no posts");
        }
        var posts = board.getHex(plain).getDecorations();
        assertEquals(6, posts.size(), "Pillars on plain ground stay pillars");
        assertTrue(posts.stream().allMatch(o -> o.asset().equals(BoardSceneryLayouts.PILLAR)
              && o.placement().equals(BoardDecoration.Placement.ground()) && o.stretch().equals(BoardDecoration.Stretch.NONE)));
        assertEquals(1, posts.stream().map(BoardDecoration::group).distinct().count());
        assertFalse(HexAppearance.pillars(board.getHex(plain).getAppearance()));
        // The trees of the road beneath a deck stand on the ground; a car park's cars stand on the deck.
        var roadside = board.getHex(trees).getDecorations();
        assertFalse(roadside.isEmpty());
        assertTrue(roadside.stream().allMatch(o -> o.placement().equals(BoardDecoration.Placement.ground())), roadside.toString());
        var cars = board.getHex(parked).getDecorations().stream().filter(o -> o.asset().startsWith("scenery/vehicles/car")).toList();
        assertFalse(cars.isEmpty());
        assertTrue(cars.stream().allMatch(o -> o.placement().receiver().terrain().equals("bridge")));

        // The converted board, its pillar tokens kept, draws the toggle and the six posts on plain ground once.
        var tiles = BoardTiles.captureAll(board);
        for (Coords at : terrain.keySet()) {
            var tile = tiles.get(at.getX() * board.getHeight() + at.getY());
            long drawn = tile.features().stream().filter(f -> f.asset().equals(BoardSceneryLayouts.PILLAR)).count();
            assertEquals(pillared.contains(at), HexAppearance.pillars(tile.appearance()), at.toString());
            assertEquals(at.equals(plain) ? 6 : 0, drawn, at + ": posts");
            assertTrue(tile.features().stream().allMatch(f -> f.decoration() != null || f.kind() != BoardScene.FeatureKind.SCENERY),
                  at + ": no legacy rows");
        }
    }

    @Test
    void picnicTablesBecomeIndividualTablesAndTreesInOneGroup() {
        Board legacy = fixture();
        Board board = fixture();
        BoardSceneryLayouts.convert(board);
        assertTrue(board.isNativeFormat());

        Hex tables = board.getHex(TABLES);
        assertTrue(tables.containsTerrain(Terrains.FLUFF), "The decoded token stays for the 2D board");
        List<BoardDecoration> objects = tables.getDecorations();
        assertEquals(17, objects.size());
        assertEquals(15, objects.stream().filter(o -> o.asset().equals("scenery/parks/picnic-table")).count());
        assertEquals(Set.of("L0_0_g0"), objects.stream().map(BoardDecoration::group).collect(java.util.stream.Collectors.toSet()));
        for (int i = 0; i < objects.size(); i++) { assertEquals("L0_0_" + i, objects.get(i).id()); }
        assertEquals(BoardDecoration.Placement.ground(), objects.getFirst().placement());

        // The trees are the species and headings the hex's seed draws for the layout's park tree rows.
        var legacyTrees = parkTrees(legacy.getHex(TABLES));
        var trees = objects.stream().filter(o -> !o.asset().startsWith("scenery/")).toList();
        assertEquals(2, legacyTrees.size());
        for (int i = 0; i < 2; i++) {
            assertEquals(legacyTrees.get(i).asset(), trees.get(i).asset());
            assertEquals(legacyTrees.get(i).rotation(), trees.get(i).rotation(), 1e-4);
            assertEquals(legacyTrees.get(i).x(), trees.get(i).x() * BoardGeometry.TILE_WIDTH, 1e-3);
            assertEquals(legacyTrees.get(i).y(), trees.get(i).y() * BoardGeometry.TILE_HEIGHT, 1e-3);
        }
    }

    @Test
    void rowsAreHandCheckedAndOnlyDecodableTokensLeave() throws Exception {
        Board board = fixture();
        BoardSceneryLayouts.convert(board);

        // bevel3 is the roof bevel turned by -120 degrees about the hex centre (generator row, model px).
        Hex roof = board.getHex(ROOF);
        assertTrue(roof.containsTerrain(Terrains.FLUFF));
        BoardDecoration bevel = roof.getDecorations().getFirst();
        assertEquals(1, roof.getDecorations().size());
        assertEquals("scenery/roofs/bevel", bevel.asset());
        assertEquals(-19.486 / BoardGeometry.TILE_WIDTH, bevel.x(), 1e-5);
        assertEquals(11.25 / 72, bevel.y(), 1e-5);
        assertEquals(-120, bevel.rotation(), 1e-4);
        assertNull(bevel.group(), "One object forms no group");
        assertEquals(BoardDecoration.Placement.surface("building", "roof", 0), bevel.placement());

        // pillars1 on the bridge: the bridge's Pillars toggle, no objects (the round trip below keeps it).
        Hex bridge = board.getHex(BRIDGE);
        assertTrue(bridge.getDecorations().isEmpty());
        assertTrue(bridge.containsTerrain(Terrains.FLUFF));
        assertEquals(HexAppearance.PILLARS, bridge.getAppearance().get("bridge"));

        // Road level 2 keeps its rule terrain and becomes the native Alley; its trees become objects.
        Hex road = board.getHex(ROAD);
        assertEquals(2, road.terrainLevel(Terrains.ROAD));
        assertEquals(6, road.getDecorations().size());
        assertTrue(road.getDecorations().stream().allMatch(o -> BoardTreeDistribution.PARK.contains(o.asset())));

        // A seaport yard is the woods' container yard look, no object; its woods stay for the rules and draw the yard
        // instead of trees, as the legacy art showed.
        Hex seaport = board.getHex(SEAPORT);
        assertTrue(seaport.containsTerrain(Terrains.FLUFF));
        assertEquals(1, seaport.terrainLevel(Terrains.WOODS));
        assertTrue(seaport.getDecorations().isEmpty());
        assertEquals(new HexAppearance("woods/container-yard/rows", null, null, null), seaport.getAppearance().get("vegetation"));
        var yard = BoardFeatures.capture(seaport, SEAPORT, Map.of(), Set.of(), board::getHex);
        assertTrue(yard.stream().noneMatch(f -> f.kind() == BoardScene.FeatureKind.TREE), "No woods trees grow in the container yard");
        assertTrue(yard.stream().anyMatch(f -> f.asset().equals("scenery/seaport/yard/light-rows")
              && BoardFeatures.terrainOwned(f.decoration())));

        // Ice and fuel tanks carry what stands on them.
        assertEquals(BoardDecoration.Placement.surface("ice", "top", 0), board.getHex(ICE).getDecorations().getFirst().placement());
        assertEquals(BoardDecoration.Placement.surface("fuelTank", "top", 0),
              board.getHex(TANK).getDecorations().getFirst().placement());

        // Maglev artwork becomes authoritative N/S rail terrain; its transient conversion marker is removed.
        assertFalse(board.getHex(MAGLEV).containsTerrain(Terrains.FLUFF), "The maglev token is fully consumed");
        assertEquals(2, board.getHex(MAGLEV).terrainLevel(Terrains.RAIL), "Maglev remains its distinct rail kind");
        assertTrue(board.getHex(MAGLEV).getDecorations().isEmpty(), "The route derives from terrain, not a stored object");
        var route = megamek.common.board.RailRoute.markers(board.getHex(MAGLEV), megamek.common.board.RailRoute.Kind.MAGLEV);
        assertEquals(1, route.size());
        assertEquals(9, route.getFirst().connections());
        assertNull(route.getFirst().group());
        assertEquals(9, board.getHex(INVALID).terrainLevel(Terrains.FLUFF));

        // The imported document round-trips through .board2, which holds the objects, not the tokens they came from.
        var text = new ByteArrayOutputStream();
        BoardFile.write(board, text);
        Board reread = BoardFile.readNative(text.toString(StandardCharsets.UTF_8));
        for (int x = 0; x < board.getWidth(); x++) {
            assertEquals(BoardFile.hexNode(BoardSceneryLayouts.withoutTakenTokens(board.getHex(x, 0))),
                  BoardFile.hexNode(reread.getHex(x, 0)));
        }
        assertFalse(reread.getHex(TABLES).containsTerrain(Terrains.FLUFF), "A taken token is not written");
        assertFalse(reread.getHex(INVALID).containsTerrain(Terrains.FLUFF), "A code without artwork is dropped");
    }

    @Test
    void herdsImportAsTheAnimalsOfTheHerdTheTilesetChoseForEachHex() {
        Board legacy = Board.createEmptyBoard(8, 1), board = Board.createEmptyBoard(8, 1);
        for (int x = 0; x < 8; x++) {
            legacy.setHex(new Coords(x, 0), new Hex(0, "fluff:13:01", ""));
            board.setHex(new Coords(x, 0), new Hex(0, "fluff:13:01", ""));
        }
        BoardSceneryLayouts.convert(board);
        Set<String> herds = new java.util.HashSet<>();
        for (int x = 0; x < 8; x++) {
            Coords at = new Coords(x, 0);
            List<String> chosen = TilesetScenery.resolve(legacy.getHex(at), false).scenery().models();
            assertEquals(1, chosen.size(), at.toString());
            herds.add(chosen.getFirst());
            var rows = BoardSceneryLayouts.layout(chosen.getFirst()).components();
            var animals = board.getHex(at).getDecorations();
            assertTrue(board.getHex(at).containsTerrain(Terrains.FLUFF));
            assertEquals(rows.size(), animals.size(), chosen.getFirst());
            assertEquals(Set.of("L" + x + "_0_g0"), animals.stream().map(BoardDecoration::group)
                  .collect(java.util.stream.Collectors.toSet()), "One group per herd");
            for (int i = 0; i < rows.size(); i++) {
                assertEquals(rows.get(i).asset(), animals.get(i).asset());
                assertEquals(rows.get(i).x() / BoardGeometry.TILE_WIDTH, animals.get(i).x(), 1e-6);
                assertEquals(rows.get(i).rotation(), animals.get(i).rotation(), 1e-4);
            }
        }
        assertTrue(herds.size() > 1, "The tileset picks the cattle herd by position: " + herds);
    }

    @Test
    void emblemSetsBecomeOneFullEmblemAndOneHexVariantsKeepTheirTurnAndScale() throws Exception {
        var file = megamek.common.Configuration.boardsDir().toPath().resolve("Templates/LandingPadsFactionSymbols.board");
        Board legacy = BoardFile.read(file), board = BoardFile.read(file);
        assertEquals(List.of(), BoardSceneryLayouts.convert(board), "Every set on the template is complete");
        Set<String> emblems = new java.util.HashSet<>();
        int sets = 0, singles = 0;
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                var before = legacy.getHex(x, y).getTerrain(Terrains.FLUFF);
                if (before == null || before.getLevel() < 50 || before.getLevel() > 62) { continue; }
                assertTrue(board.getHex(x, y).containsTerrain(Terrains.FLUFF), "Every piece keeps its token: " + x + "," + y);
                for (var object : board.getHex(x, y).getDecorations()) {
                    assertEquals("decal", object.kind());
                    assertTrue(object.asset().startsWith("decal/emblems/"), object.asset());
                    if (object.scale() == 1) {
                        // One emblem, on the centre piece's hex, spanning its six neighbours.
                        sets++;
                        emblems.add(object.asset());
                        assertEquals(10, before.getExits(), "The emblem sits on the centre hex");
                        assertFalse(object.clipToHex());
                        assertEquals(0, object.x()); assertEquals(0, object.y()); assertEquals(0, object.rotation());
                    } else {
                        singles++;
                        int variant = before.getExits();
                        assertTrue(variant <= 6 && object.clipToHex() && object.scale() < .51, object.toString());
                        assertEquals(variant % 3 == 1 ? 0 : variant % 3 == 2 ? -30 : 30, object.rotation(), "Variant " + variant);
                    }
                }
            }
        }
        assertEquals(13, sets);
        assertEquals(13, emblems.size(), "One set of each emblem");
        assertEquals(72, singles);
    }

    @Test
    void anIncompleteSetPaintsExactlyItsPiecesAndOneCutByTheEdgeGetsItsFullEmblemAndAnIssue() {
        // Only the N and S pieces of a Red Cross set: the centre hex, on the board, has no piece of its own. Each piece
        // shows its part of the emblem on the inferred centre, clipped to its hex; the centre paints nothing.
        Board board = Board.createEmptyBoard(3, 3);
        board.setHex(new Coords(1, 0), new Hex(0, "fluff:62:7", ""));
        board.setHex(new Coords(1, 2), new Hex(0, "pavement:1;fluff:62:13", ""));
        assertTrue(BoardSceneryLayouts.convert(board).isEmpty());
        assertTrue(board.getHex(new Coords(1, 1)).getDecorations().isEmpty());
        for (Coords piece : List.of(new Coords(1, 0), new Coords(1, 2))) {
            var emblem = board.getHex(piece).getDecorations();
            assertEquals(1, emblem.size());
            assertEquals("decal/emblems/red-cross", emblem.getFirst().asset());
            assertEquals(1, emblem.getFirst().scale());
            assertTrue(emblem.getFirst().clipToHex());
            assertEquals(piece.getY() == 0 ? -1 : 1, emblem.getFirst().y(), 1e-6, "On the centre");
        }
        assertTrue(board.getHex(new Coords(1, 2)).containsTerrain(Terrains.FLUFF));
        assertTrue(board.getHex(new Coords(1, 2)).containsTerrain(Terrains.PAVEMENT), "Rule terrain stays");

        // A set cut by the board edge: its centre lies north of the board, so its piece's hex owns it at that offset.
        // In column 0 no other piece's hex is on the board: the full emblem, and an issue for the rest.
        Board cut = Board.createEmptyBoard(3, 3);
        cut.setHex(new Coords(0, 0), new Hex(0, "fluff:62:13", ""));
        assertEquals(1, BoardSceneryLayouts.convert(cut).size());
        var offset = cut.getHex(new Coords(0, 0)).getDecorations().getFirst();
        assertFalse(offset.clipToHex());
        assertEquals(0, offset.x(), 1e-6);
        assertEquals(1, offset.y(), 1e-6, "One hex height north");
    }

    @Test
    void carParksBecomeStandardDecalsWithEachCarInItsOwnBayKeepingItsPaint() {
        String[] codes = { "00", "01", "02", "03", "04", "05", "06", "07", "98", "99" };
        Board board = Board.createEmptyBoard(codes.length, 1), legacy = Board.createEmptyBoard(codes.length, 1);
        for (int x = 0; x < codes.length; x++) {
            board.setHex(new Coords(x, 0), new Hex(0, "pavement:1;fluff:5:" + codes[x], ""));
            legacy.setHex(new Coords(x, 0), new Hex(0, "pavement:1;fluff:5:" + codes[x], ""));
        }
        BoardSceneryLayouts.convert(board);
        for (int x = 0; x < codes.length; x++) {
            var objects = board.getHex(x, 0).getDecorations();
            var plates = objects.stream().filter(o -> o.kind().equals("decal")).toList();
            var cars = objects.stream().filter(o -> o.asset().startsWith("scenery/vehicles/car")).toList();
            assertTrue(plates.size() == 1 || plates.size() == 2, codes[x]);
            assertEquals(1, objects.stream().map(BoardDecoration::group).distinct().count(), "One car-park group");
            // 00-07 get their lane as a road and stand beside it; 98/99 have no lane and stand back to back.
            assertRows(plates, x < 8, codes[x]);
            // The cars keep their paint: the same colours as the legacy key's car rows.
            var rows = BoardSceneryLayouts.layout(TilesetScenery.resolve(legacy.getHex(x, 0), false).scenery().models()
                  .getFirst()).components();
            assertEquals(rows.stream().filter(r -> r.asset().startsWith("scenery/vehicles/car")).map(r -> r.colours().slots()).sorted(
                  java.util.Comparator.comparing(Object::toString)).toList(), cars.stream().map(c -> c.colours().slots())
                  .sorted(java.util.Comparator.comparing(Object::toString)).toList());
            for (var car : cars) {
                // Inside one of the decals' bay rows (model px, in that decal's frame), and no two cars share a bay.
                double px = car.x() * BoardGeometry.TILE_WIDTH, py = car.y() * BoardGeometry.TILE_HEIGHT;
                assertTrue(plates.stream().anyMatch(plate -> {
                    var size = megamek.common.board.BoardDecalArt.footprint(plate.asset());
                    double turn = Math.toRadians(-plate.rotation());
                    double dx = px - plate.x() * BoardGeometry.TILE_WIDTH, dy = py - plate.y() * BoardGeometry.TILE_HEIGHT;
                    double u = dx * Math.cos(turn) - dy * Math.sin(turn), v = dx * Math.sin(turn) + dy * Math.cos(turn);
                    return Math.abs(u) < size.width() / 2 && Math.abs(v) < size.height() / 2;
                }), codes[x] + " " + car);
                // On a slot where its plate paints it: a mirrored plate's art, flipped along the row, keeps its bays.
                assertTrue(plates.stream().anyMatch(plate -> {
                    var size = megamek.common.board.BoardDecalArt.footprint(plate.asset());
                    var uv = BoardDecals.paintUv(plate);
                    double dx = (car.x() - plate.x()) * BoardGeometry.width(), dy = (car.y() - plate.y()) * BoardGeometry.height();
                    return size.slots().stream().anyMatch(slot -> Math.abs(uv.u(dx, dy) - (.5 + slot.x() / size.width())) < 1e-3
                          && Math.abs(uv.v(dx, dy) - (.5 - slot.y() / size.height())) < 1e-3);
                }), codes[x] + " on a painted slot: " + car);
                for (var other : cars) {
                    if (other != car) {
                        assertTrue(Math.hypot(px - other.x() * BoardGeometry.TILE_WIDTH,
                              py - other.y() * BoardGeometry.TILE_HEIGHT) > 4.9, codes[x] + ": one car per bay");
                    }
                }
            }
        }
    }

    /**
     * A car park's two decal rows (model px): beside a road, each row's open side on the road's fading asphalt edge
     * ({@link BoardRoad.Kind#halfWidth} less {@link GpuRoads#FEATHER}), so the gray verge is hidden and the carriageway
     * clear; without one, back to back and mirrored, their closed sides meeting on the line through the hex centre.
     * Either way the rows lie on opposite sides. Full-size bays may cross hex edges, so parking paint is not clipped.
     */
    private static void assertRows(List<BoardDecoration> plates, boolean road, String name) {
        assertEquals(2, plates.size(), name);
        for (var plate : plates) {
            var size = megamek.common.board.BoardDecalArt.footprint(plate.asset());
            double turn = Math.toRadians(plate.rotation()), half = size.height() * plate.scale() / 2;
            double x = plate.x() * BoardGeometry.TILE_WIDTH, y = plate.y() * BoardGeometry.TILE_HEIGHT;
            // How far the row's centre lies along its back direction (its +y, toward its closed side).
            double toward = -Math.sin(turn) * x + Math.cos(turn) * y;
            assertEquals(!road, plate.mirror(), name);
            assertFalse(plate.clipToHex(), name + ": complete bays across hex edges");
            if (road) {
                double edge = toward - half;
                assertTrue(edge >= BoardRoad.Kind.PAVED.halfWidth - GpuRoads.FEATHER && edge < BoardRoad.Kind.PAVED.halfWidth,
                      name + ": road-side edge " + edge);
            } else {
                assertEquals(0, toward + half, 1e-3, name + ": the closed side on the centre line");
            }
        }
        // The second row is the first turned half a turn about the hex centre.
        var a = plates.get(0);
        var b = plates.get(1);
        assertEquals(0, a.x() + b.x(), 1e-5, name);
        assertEquals(0, a.y() + b.y(), 1e-5, name);
        assertEquals(180, Math.abs(a.rotation() - b.rotation()) % 360, 1e-4, name);
    }

    /** A hex's ROAD as level, exits and whether its exits are written out, or "none". */
    private static String road(Hex hex) {
        var road = hex.getTerrain(Terrains.ROAD);
        return road == null ? "none"
              : road.getLevel() + ":" + road.getExits() + (road.hasExitsSpecified() ? " specified" : " automatic");
    }

    @Test
    void groundCarParksGetTheirLaneAsARoadAlongTheAisle() throws Exception {
        // cars_1..8, the laneless cars_2b/3b, and cars_3 on bare ground; each car park on its own hex.
        String[] terrain = { "pavement:1;fluff:5:00", "pavement:1;fluff:5:01", "pavement:1;fluff:5:02",
              "pavement:1;fluff:5:03", "pavement:1;fluff:5:04", "pavement:1;fluff:5:05", "pavement:1;fluff:5:06",
              "pavement:1;fluff:5:07", "pavement:1;fluff:5:98", "pavement:1;fluff:5:99", "fluff:5:02" };
        // The legacy render's lane per sprite: NE+SW 18, SE+NW 36, N+S 9; none for 2b/3b.
        int[] lanes = { 18, 36, 9, 18, 36, 9, 36, 9, 0, 0, 9 };
        Board board = Board.createEmptyBoard(2 * terrain.length + 1, 3);
        for (int i = 0; i < terrain.length; i++) { board.setHex(new Coords(1 + 2 * i, 1), new Hex(0, terrain[i], "")); }
        BoardSceneryLayouts.convert(board);
        var text = new ByteArrayOutputStream();
        BoardFile.write(board, text);
        Board reread = BoardFile.readNative(text.toString(StandardCharsets.UTF_8));
        // The board's hex is regular to a 32nd: its diagonal borders lie a hair off the decals' 60-degree turns.
        double square = Math.sin(Math.toRadians(.5));
        for (int i = 0; i < terrain.length; i++) {
            Coords at = new Coords(1 + 2 * i, 1);
            Hex hex = board.getHex(at);
            String expected = lanes[i] == 0 ? "none" : "1:" + lanes[i] + " specified";
            assertEquals(expected, road(hex), terrain[i]);
            assertEquals(expected, road(reread.getHex(at)), terrain[i] + " after a .board2 round trip");
            var plates = hex.getDecorations().stream().filter(o -> o.kind().equals("decal")).toList();
            assertEquals(2, plates.size(), terrain[i]);
            // The two decals' centres lie across their lane: square to the road's course through each exit edge.
            double ax = (plates.get(1).x() - plates.get(0).x()) * BoardGeometry.TILE_WIDTH;
            double ay = (plates.get(1).y() - plates.get(0).y()) * BoardGeometry.TILE_HEIGHT;
            for (int direction = 0; direction < 6; direction++) {
                if ((lanes[i] & 1 << direction) == 0) { continue; }
                Coords next = at.translated(direction);
                double ex = (BoardGeometry.centerX(next) - BoardGeometry.centerX(at)) / BoardGeometry.hexScale() / 2;
                double ey = (BoardGeometry.centerY(next) - BoardGeometry.centerY(at)) / BoardGeometry.hexScale() / 2;
                double cos = (ax * ex + ay * ey) / Math.hypot(ax, ay) / Math.hypot(ex, ey);
                assertTrue(Math.abs(cos) < square, terrain[i] + ": the lane runs along the aisle, direction " + direction);
            }
        }
    }

    @Test
    void carParksOffTheGroundStayDecalsWithoutARoad() {
        // Car-park art on a roof, deck, industrial top, fuel tank, ice or water was decoration, and the renderer draws no
        // road on a liquid: none of them gets a road, and their rows stand back to back.
        String[] supports = { "building:2;bldg_elev:2;bldg_cf:50", "bridge:1:09;bridge_cf:40;bridge_elev:2",
              "heavy_industrial:2", "fuel_tank:1;fuel_tank_elev:2;fuel_tank_cf:50;fuel_tank_magn:100", "water:2;ice:1",
              "water:1", "hazardous_liquid:1", "magma:2" };
        Board board = Board.createEmptyBoard(2 * supports.length + 1, 3);
        for (int i = 0; i < supports.length; i++) {
            board.setHex(new Coords(1 + 2 * i, 1), new Hex(0, supports[i] + ";fluff:5:02", ""));
        }
        BoardSceneryLayouts.convert(board);
        for (int i = 0; i < supports.length; i++) {
            Hex hex = board.getHex(new Coords(1 + 2 * i, 1));
            assertEquals("none", road(hex), supports[i]);
            assertTrue(hex.containsTerrain(Terrains.FLUFF), supports[i]);
            assertRows(hex.getDecorations().stream().filter(o -> o.kind().equals("decal")).toList(), false, supports[i]);
        }
        assertTrue(board.getHex(new Coords(1, 1)).getDecorations().stream().filter(o -> o.kind().equals("decal"))
              .allMatch(o -> o.placement().receiver().terrain().equals("building")), "The roof carries the decals");
    }

    @Test
    void anAuthoredRoadOnACarParkIsKept() {
        // An automatic road that also exits NE, off the N-S lane, toward a road beside it; and a dead end to the south.
        // An unknown road level (5) is drawn as a paved road (HexTile.road, BoardRoad.kind).
        Coords automatic = new Coords(1, 1), beside = automatic.translated(1), deadEnd = new Coords(5, 1),
              unknown = new Coords(7, 1);
        Board board = Board.createEmptyBoard(9, 3);
        board.setHex(automatic, new Hex(0, "pavement:1;road:1;fluff:5:02", ""));
        board.setHex(beside, new Hex(0, "road:1", ""));
        board.setHex(deadEnd, new Hex(0, "road:1:08;fluff:5:02", ""));
        board.setHex(unknown, new Hex(0, "road:5:09;fluff:5:02", ""));
        assertEquals("1:2 automatic", road(board.getHex(automatic)));
        List<String> before = List.of(road(board.getHex(automatic)), road(board.getHex(beside)), road(board.getHex(deadEnd)),
              road(board.getHex(unknown)));
        BoardSceneryLayouts.convert(board);
        assertEquals(before, List.of(road(board.getHex(automatic)), road(board.getHex(beside)), road(board.getHex(deadEnd)),
              road(board.getHex(unknown))));
        assertTrue(board.getHex(automatic).containsTerrain(Terrains.FLUFF));
        // The roads run between their rows.
        for (Coords at : List.of(automatic, deadEnd, unknown)) {
            assertRows(board.getHex(at).getDecorations().stream().filter(o -> o.kind().equals("decal")).toList(),
                  true, at.toString());
        }
    }

    private static final Coords PARK = new Coords(2, 2);

    /** A cars_3 car park (lane N-S) without pavement at the centre of a 5 x 5 board, among the given hexes. */
    private static Board lane(Map<Coords, String> around) {
        Board board = Board.createEmptyBoard(5, 5);
        board.setHex(PARK, new Hex(0, "fluff:5:02", ""));
        around.forEach((at, terrain) -> board.setHex(at, new Hex(0, terrain, "")));
        return board;
    }

    @Test
    void theLaneJoinsOnlyRoadsThatPointAlongIt() {
        Coords north = PARK.translated(0), northEast = PARK.translated(1), southEast = PARK.translated(2),
              south = PARK.translated(3);
        // No neighbouring road, pavement all around with roads exiting to pavement: the lane only, no roundabout.
        Map<Coords, String> paved = new HashMap<>();
        for (int direction = 0; direction < 6; direction++) { paved.put(PARK.translated(direction), "pavement:1"); }
        Board board = lane(paved);
        assertTrue(board.getRoadsAutoExit());
        BoardSceneryLayouts.convert(board);
        assertEquals("1:9 specified", road(board.getHex(PARK)));

        // A dead end from the north: the two now point at each other, and the neighbour is unchanged.
        board = lane(Map.of(north, "road:1:08"));
        BoardSceneryLayouts.convert(board);
        assertEquals("1:9 specified", road(board.getHex(PARK)));
        assertEquals("1:8 specified", road(board.getHex(north)));

        // Roads at both ends join into one through road.
        board = lane(Map.of(north, "road:1:08", south, "road:1:01"));
        BoardSceneryLayouts.convert(board);
        assertEquals(List.of("1:9 specified", "1:8 specified", "1:1 specified"),
              List.of(road(board.getHex(PARK)), road(board.getHex(north)), road(board.getHex(south))));

        // A road pointing in off the lane still ends at the car park's edge, as the legacy lane left it.
        board = lane(Map.of(northEast, "road:1:16"));
        BoardSceneryLayouts.convert(board);
        assertEquals(List.of("1:9 specified", "1:16 specified"), List.of(road(board.getHex(PARK)), road(board.getHex(northEast))));

        // An automatic side road keeps the exits it loaded with, written out: no arm toward the car park.
        Coords beyond = southEast.translated(2);
        board = lane(Map.of(southEast, "road:1", beyond, "road:1"));
        assertEquals("1:4 automatic", road(board.getHex(southEast)));
        BoardSceneryLayouts.convert(board);
        assertEquals(List.of("1:9 specified", "1:4 specified", "1:32 automatic"),
              List.of(road(board.getHex(PARK)), road(board.getHex(southEast)), road(board.getHex(beyond))));

        // An automatic road on the lane gains its exit toward the car park and joins it.
        board = lane(Map.of(south, "road:1", south.translated(3), "road:1"));
        assertEquals("1:8 automatic", road(board.getHex(south)));
        BoardSceneryLayouts.convert(board);
        assertEquals(List.of("1:9 specified", "1:9 automatic"), List.of(road(board.getHex(PARK)), road(board.getHex(south))));

        // The same road beside a second car park, off that one's lane: it still joins the lane and grows no arm toward
        // the second car park, whether the scan meets that car park first (NW of the road) or last (NE).
        for (int side : new int[] { 5, 1 }) {
            Coords other = south.translated(side);
            board = lane(Map.of(south, "road:1", south.translated(3), "road:1", other, "fluff:5:02"));
            BoardSceneryLayouts.convert(board);
            assertEquals(List.of("1:9 specified", "1:9 specified", "1:9 specified"),
                  List.of(road(board.getHex(PARK)), road(board.getHex(south)), road(board.getHex(other))), "side " + side);
        }
    }

    @Test
    void treesStoreTheirBareSpeciesAndTakeTheirSnowFormOnSnowUnlessKeptBare() throws Exception {
        Board legacy = Board.createEmptyBoard(1, 1), imported = Board.createEmptyBoard(1, 1);
        legacy.setHex(TABLES, new Hex(0, "snow:1;" + PICNIC, ""));
        imported.setHex(TABLES, new Hex(0, "snow:1;" + PICNIC, ""));
        BoardSceneryLayouts.convert(imported);
        List<String> legacyTrees = parkTrees(legacy.getHex(TABLES)).stream().map(row -> row.asset() + "-snow").toList();
        var trees = imported.getHex(TABLES).getDecorations().stream().filter(o -> BoardFeatures.hasSnowForm(o.asset())).toList();
        assertEquals(2, legacyTrees.size());
        assertEquals(legacyTrees, trees.stream().map(o -> o.asset() + "-snow").toList(), "Trees store their bare species");
        assertEquals(legacyTrees, drawnTrees(imported.getHex(TABLES)), "On snow they draw as the legacy winter trees");

        // The inspector's Snow toggle keeps one tree bare on the snow; the choice is saved in .board2.
        var session = new BoardEditorSession(BoardEditorBlueprint.get());
        Coords at = new Coords(1, 1);
        // The editor's document holds the objects, not the token they came from.
        Hex hex = BoardSceneryLayouts.withoutTakenTokens(imported.getHex(TABLES)).duplicate();
        hex.setDecorations(hex.getDecorations().stream().map(o -> o.withGroup(null)).toList());
        session.board().setHex(at, hex);
        session.pointer(at, 0, 0, false);
        session.command(new BoardEditorSession.Command(BoardEditorSession.Action.SELECT_OBJECT, "", trees.getFirst().id()), null);
        session.command(new BoardEditorSession.Command(BoardEditorSession.Action.OBJECT_VALUE, "bare", "true"), null);
        Hex edited = session.board().getHex(at);
        assertEquals(List.of(trees.getFirst().asset(), legacyTrees.get(1)), drawnTrees(edited));
        var text = new ByteArrayOutputStream();
        BoardFile.write(session.board(), text);
        assertEquals(edited.getDecorations(), BoardFile.readNative(text.toString(StandardCharsets.UTF_8)).getHex(at).getDecorations());
    }

    /** The park tree rows of the hex's legacy layout, their species and headings drawn from its seed. */
    private static List<BoardSceneryLayouts.Component> parkTrees(Hex legacy) {
        String key = TilesetScenery.resolve(legacy, false).scenery().models().getFirst();
        return BoardSceneryLayouts.layout(key).chosen(TABLES, BoardTreeDistribution.PARK, BoardTreeDistribution.NATURAL)
              .stream().filter(BoardSceneryLayouts.Component::tree).toList();
    }

    private static List<String> drawnTrees(Hex hex) {
        return BoardFeatures.capture(hex, TABLES, Map.of(), Set.of(), coords -> null).stream()
              .filter(f -> f.decoration() != null && BoardFeatures.hasSnowForm(f.decoration().asset()))
              .map(BoardScene.Feature::asset).toList();
    }

    @Test
    void roadLevelTwoTreesDecodeOnlyOnLegacyBoardsAndAConvertedBoardDrawsItsAlley() {
        for (boolean nativeBoard : new boolean[] { false, true }) {
            Board board = fixture();
            board.setNativeFormat(nativeBoard);
            assertEquals(!nativeBoard, !TilesetScenery.resolve(board.getHex(ROAD), nativeBoard).scenery().models().isEmpty(),
                  "Road level 2 is legacy road-with-trees art, natively the Alley finish");
            assertEquals(List.of("scenery/saxarba/SMV_Fluff/FluffSystem-07-Garden-02-Table-1-03"),
                  TilesetScenery.resolve(board.getHex(TABLES), nativeBoard).scenery().models());
            BoardSceneryLayouts.convert(board);
            assertEquals(!nativeBoard, !board.getHex(ROAD).getDecorations().isEmpty(), "Only a legacy board's trees decode");
            // Either way the drawn board shows the Alley and no rows: its trees are objects or were never there.
            var road = BoardTiles.captureAll(board).get(ROAD.getX() * board.getHeight() + ROAD.getY());
            assertEquals(BoardRoad.Kind.ALLEY, road.road());
            assertTrue(road.features().stream().noneMatch(f -> f.kind() == BoardScene.FeatureKind.SCENERY
                  || f.kind() == BoardScene.FeatureKind.TREE && f.decoration() == null));
        }
    }

    @Test
    void stampsTakeTheHexRoadExitsAndFreshIds() {
        Hex road = new Hex(0, "road:1:09", "");
        var members = BoardSceneryLayouts.stamp("scenery/saxarba/roads/road_trees{exits}", road, new Coords(3, 4));
        assertEquals(6, members.size());
        String group = members.getFirst().group();
        UUID.fromString(group);
        for (BoardDecoration member : members) {
            UUID.fromString(member.id());
            assertEquals(group, member.group());
        }
        Hex crossed = new Hex(0, "road:1:09;rail:1:18", "");
        var at = new Coords(3, 4);
        var clearance = BoardRoad.clearance(at, crossed, ignored -> null);
        for (var member : BoardSceneryLayouts.stamp("scenery/saxarba/roads/road_trees{exits}", crossed, at)) {
            assertTrue(clearance.distance((float) member.x() * BoardGeometry.TILE_WIDTH,
                  (float) member.y() * BoardGeometry.TILE_HEIGHT) >= BoardRoad.SHOULDER,
                  "Roadside trunks clear the road and crossing rail; natural canopies may overhang the verge");
        }
        var error = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
              () -> BoardSceneryLayouts.stamp("scenery/saxarba/roads/road_trees{exits}", new Hex(0), new Coords(3, 4)));
        assertEquals("Paint a road first", error.getMessage());
    }

    @Test
    void paintingAStampIsOneUndoStep() {
        var session = new BoardEditorSession(BoardEditorBlueprint.get());
        Coords at = new Coords(2, 2);
        session.command(new BoardEditorSession.Command(BoardEditorSession.Action.ASSET, "stamp/roadside-trees"), null);
        session.pointer(at, 0, 0, false);
        session.finishStroke();
        assertEquals("Paint a road first", session.snapshot().message());
        assertTrue(session.board().getHex(at).getDecorations().isEmpty());

        session.board().setHex(at, new Hex(0, "road:1:09", ""));
        session.pointer(at, 0, 0, false);
        session.pointer(at, .1, .1, true);
        session.finishStroke();
        var objects = session.board().getHex(at).getDecorations();
        assertEquals(6, objects.size(), "One press places the layout once");
        assertEquals(1, objects.stream().map(BoardDecoration::group).distinct().count());
        assertEquals(6, session.snapshot().selection().size(), "The new stamp is selected as its whole group");
        assertEquals(objects.getFirst().group(), session.snapshot().group());
        session.command(new BoardEditorSession.Command(BoardEditorSession.Action.UNDO), null);
        assertTrue(session.board().getHex(at).getDecorations().isEmpty());
    }

    @Test
    void corpusRoadLevelsUseTheGamesPavedRuleWithoutRewritingTerrainOrExits() {
        for (int level : new int[] { 0, 1, 5, 6, 7, 17, 34, 90 }) {
            for (int exits = 0; exits < 64; exits++) {
                var hex = new Hex(0, "road:" + level + ":" + exits, "");
                String terrain = hex.getTerrain(Terrains.ROAD).toString();
                assertTrue(hex.hasPavedRoad(), "The authoritative game rule accepts road level " + level);
                assertTrue(HexTile.road(hex));
                assertEquals(BoardRoad.Kind.PAVED, BoardRoad.capture(hex));
                assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
                var at = new Coords(3, 3);
                var road = BoardRoad.layout(at, exits, BoardRoad.capture(hex), direction -> BoardRoad.Kind.NONE);
                for (int edge = 0; edge < 6; edge++) {
                    int next = (edge + 1) % 6;
                    float x = (HexTile.cornerOffsetX(edge, HexTile.MODEL_WIDTH)
                          + HexTile.cornerOffsetX(next, HexTile.MODEL_WIDTH)) * .49f;
                    float y = (HexTile.cornerOffsetY(edge, HexTile.HEIGHT)
                          + HexTile.cornerOffsetY(next, HexTile.HEIGHT)) * .49f;
                    assertEquals((exits & 1 << HexTile.edgeDirection(edge)) != 0, road.footprint(0).contains(x, y),
                          "The drawn road keeps only its authored exits");
                }
                assertEquals(terrain, hex.getTerrain(Terrains.ROAD).toString());
            }
        }
    }

    @Test
    void unusualPavedApproachesGiveImportedBridgesPavedDecks() {
        for (int level : new int[] { 0, 5, 6, 7, 34, 90 }) {
            Board board = Board.createEmptyBoard(3, 5);
            var deck = new Coords(1, 2);
            var approach = deck.translated(0);
            board.setHex(deck, new Hex(0, "water:1;bridge:1:9;bridge_cf:40;bridge_elev:0", ""));
            board.setHex(approach, new Hex(0, "road:" + level + ":9", ""));
            BoardSceneryLayouts.convert(board);
            assertEquals(BoardRoad.Kind.PAVED, BoardRoad.deck(board.getHex(deck)));
            assertEquals(level, board.getHex(approach).terrainLevel(Terrains.ROAD));
        }
    }
}
