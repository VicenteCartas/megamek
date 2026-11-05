/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecalArt;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.BoardTreeDistribution;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import megamek.common.board.TilesetScenery;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The converter's corpus test: converts every shipped legacy board that has scenery tokens, one at a time, as every load
 * site does ({@link BoardSceneryLayouts#convert}, image-free common code that needs no AWT), and checks the invariants
 * of b9-decisions K and K2: every token the board loaded stays in memory (2D views of a game draw its old sprite); the
 * hex every 3D view draws ({@link BoardSceneryLayouts#withoutTakenTokens}) and the {@code .board2} text hold no layer the
 * conversion took; the text reads back as that hex; converting a converted board changes nothing; rules errors and
 * roads change only by the car-park lanes. Batch 10: the conversion takes every legacy art token (FLUFF and GROUND_FLUFF
 * below the server values, ROAD_FLUFF, WATER_FLUFF, BLDG_FLUFF) as objects, a hex design or nothing, so no
 * {@code .board2} holds one; the objects and designs of its families are pinned (b10-design section 1). Opt-in
 * (minutes): set the environment variable {@code MEGAMEK_LEGACY_CORPUS=1}. Prints objects and groups per board and per
 * decoded artwork, the designs written, the maglev and rail routes and the conversion times.
 */
@EnabledIfEnvironmentVariable(named = "MEGAMEK_LEGACY_CORPUS", matches = "1")
class LegacySceneryCorpusTest {
    private record Result(String board, int objects, int groups, long millis) { }
    /** A car park's lane road by its exits. */
    private static final Map<Integer, String> LANES = Map.of(18, "NE-SW", 9, "N-S", 36, "SE-NW");

    @Test
    void everyLegacyBoardConvertsOnceKeepingItsTokens() throws Exception {
        Path models = Configuration.dataDir().toPath().resolve("models/board");
        BoardEditorBlueprint blueprint = BoardEditorBlueprint.get();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(Configuration.boardsDir().toPath())) {
            files = walk.filter(path -> path.toString().endsWith(".board")).sorted().toList();
        }
        List<Result> results = new ArrayList<>();
        Map<String, Integer> perAsset = new TreeMap<>(), untaken = new TreeMap<>(), receivers = new TreeMap<>();
        // Batch 10: the objects of its families and the designs the conversion wrote, by family.
        Map<String, Integer> families = new TreeMap<>(), designs = new TreeMap<>();
        int noTrees = 0, stretched = 0, emblemSets = 0, emblemSingles = 0, pillaredBridges = 0;
        // Maglev: markers, their joined sides (counted from both ends), adjacent markers left apart, absolute markers,
        // station and train hexes, and those whose route climbs to a neighbour while the pieces stay level.
        int markers = 0, joinedSides = 0, apart = 0, absolute = 0, stations = 0, sloped = 0;
        // Batch-10 answer 7: maglev markers on Pillars, and those a row of pillar art lifted a level above the ground.
        int pillaredMaglevs = 0, raisedMaglevs = 0;
        // Rail: markers, those riding a bridge deck, and those storing tracks other than their sides' default.
        int rails = 0, deckRails = 0, storedTracks = 0, railTerrain = 0, deckRailTerrain = 0;
        // Car-park roads: lane roads by axis and their boards, roof (or other off-ground) car parks left without a road,
        // car parks keeping an authored road, and neighbour roads whose automatic exits were written out.
        Map<String, Integer> laneRoads = new TreeMap<>();
        Set<String> laneBoards = new HashSet<>();
        int offGroundParks = 0, authoredParks = 0, keptNeighbours = 0;
        // Car-park decal rows beside their road, and folded back to back (mirrored) where no road is drawn.
        int besideRows = 0, foldedRows = 0;
        Set<String> checkedAssets = new HashSet<>();
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.ISO_8859_1);
            if (!text.contains("fluff:") && !text.contains("road:2")) { continue; }
            Board board;
            try { board = BoardFile.read(file); }
            catch (java.io.IOException | IllegalArgumentException unreadable) {
                System.out.printf("CORPUS unreadable %s: %s%n", file.getFileName(), unreadable.getMessage());
                continue;
            }
            List<String> before = new ArrayList<>(), after = new ArrayList<>();
            board.isValid(before);
            Map<Coords, Terrain> roads = new HashMap<>();
            // The art tokens each hex loaded with, which the conversion keeps in memory, and the designs it had.
            Map<Coords, List<String>> tokens = new HashMap<>();
            Set<Coords> retiredDropships = new HashSet<>();
            Map<Coords, Map<String, HexAppearance>> appearances = new HashMap<>();
            for (int y = 0; y < board.getHeight(); y++) {
                for (int x = 0; x < board.getWidth(); x++) {
                    var road = board.getHex(x, y).getTerrain(Terrains.ROAD);
                    if (road != null) { roads.put(new Coords(x, y), new Terrain(road)); }
                    var loaded = tokens(board.getHex(x, y));
                    if (!loaded.isEmpty()) { tokens.put(new Coords(x, y), loaded); }
                    var building = board.getHex(x, y).getTerrain(Terrains.BUILDING);
                    if (building != null && building.getLevel() == 4 && building.getExits() >= 69 && building.getExits() <= 75) {
                        retiredDropships.add(new Coords(x, y));
                    }
                    appearances.put(new Coords(x, y), Map.copyOf(board.getHex(x, y).getAppearance()));
                }
            }
            long start = System.nanoTime();
            var issues = BoardSceneryLayouts.convert(board);
            // Batch 10: an incomplete landing-pad set paints its pieces without an issue; only a set cut by the board edge
            // would report one, and none is.
            assertEquals(List.of(), issues, "Every emblem and pad set in the corpus is complete or partial: " + file);
            long millis = (System.nanoTime() - start) / 1_000_000;
            board.isValid(after);
            // Retiring art-only buildings can remove old connection errors. Compare by hex so
            // fixing one cell cannot conceal a new error in another.
            var addedErrors = validationErrors(after);
            addedErrors.removeAll(validationErrors(before));
            assertTrue(addedErrors.isEmpty(), () -> "New rule errors in " + file + ": " + addedErrors);
            // The only road change: a ground car park without a road gains its lane; kept neighbours only write theirs out.
            for (int y = 0; y < board.getHeight(); y++) {
                for (int x = 0; x < board.getWidth(); x++) {
                    var hex = board.getHex(x, y);
                    var road = hex.getTerrain(Terrains.ROAD);
                    var old = roads.get(new Coords(x, y));
                    boolean carPark = hex.getDecorations().stream().anyMatch(o -> o.asset().startsWith("decal/car-park/"));
                    String where = file.getFileName() + " " + x + "," + y;
                    if (old == null && road != null) {
                        assertTrue(road.getLevel() == 1 && road.hasExitsSpecified() && LANES.containsKey(road.getExits() & 63),
                              where + ": a lane road " + road);
                        assertTrue(hex.getDecorations().stream().anyMatch(o -> o.asset().startsWith("decal/car-park/")
                              && o.placement().receiver().terrain().equals("ground")), where + ": a lane road on a ground car park");
                        laneRoads.merge(LANES.get(road.getExits() & 63), 1, Integer::sum);
                        laneBoards.add(file.toString());
                    } else if (old != null) {
                        assertTrue(road != null && road.getLevel() == old.getLevel()
                              && (road.getExits() & 63) == (old.getExits() & 63)
                              && (road.hasExitsSpecified() || !old.hasExitsSpecified()), where + ": road " + old + " kept");
                        if (road.hasExitsSpecified() && !old.hasExitsSpecified()) { keptNeighbours++; }
                        if (carPark) { authoredParks++; }
                    } else if (carPark && !BoardSceneryLayouts.plainGround(hex)) {
                        offGroundParks++;
                    }
                }
            }
            int objects = 0;
            Set<String> groups = new HashSet<>();
            for (int y = 0; y < board.getHeight(); y++) {
                for (int x = 0; x < board.getWidth(); x++) {
                    var hex = board.getHex(x, y);
                    String where = file.getFileName() + " " + x + "," + y;
                    // K: the tokens stay on the converted board, so the 2D views of a game still draw the old sprites; legacy
                    // rail art becomes rail terrain, which draws its sprite (b13 ways). Retired DropShip pieces lose
                    // their building state, including their building-art token.
                    boolean laid = hex.containsAnyTerrainOf(Terrains.RAIL, Terrains.BRIDGE_RAIL);
                    assertEquals(tokens.getOrDefault(new Coords(x, y), List.of()).stream()
                          .filter(token -> !(laid && token.matches("fluff:10(:.*)?")))
                          .filter(token -> !(RailRoute.find(hex, RailRoute.Kind.MAGLEV) != null && token.matches("fluff:9:[012]")))
                          .filter(token -> !(retiredDropships.contains(hex.getCoords()) && token.startsWith("bldg_fluff:")))
                          .toList(), tokens(hex), where + ": the tokens stay");
                    if (hex.containsTerrain(Terrains.RAIL)) { railTerrain++; }
                    if (hex.containsTerrain(Terrains.BRIDGE_RAIL)) { deckRailTerrain++; }
                    // K2: every 3D view draws the hex without its taken tokens, and that hex selects nothing the
                    // conversion took (its objects stand for it). Only a token can select taken art.
                    if (tokens.containsKey(new Coords(x, y))) {
                        for (var layer : TilesetScenery.resolve(BoardSceneryLayouts.withoutTakenTokens(hex), true).layers()) {
                            assertFalse(layer.taken(), where + ": the 3D board would draw " + layer.key() + " beside its objects");
                        }
                    }
                    // Pillar art on a bridge is the bridge's Pillars toggle: no pillar objects there.
                    if (HexAppearance.pillars(hex.getAppearance())) { pillaredBridges++; }
                    if (hex.containsTerrain(Terrains.BRIDGE)) {
                        assertTrue(hex.getDecorations().stream().noneMatch(o -> o.asset().equals(BoardSceneryLayouts.PILLAR)),
                              file.getFileName() + " " + x + "," + y + ": no pillar objects on a bridge");
                    }
                    var vegetation = hex.getAppearance().get("vegetation");
                    if (BoardTreeDistribution.noTrees(hex)) { noTrees++; }
                    // Batch 10: the conversion takes every art token (objects, a design or nothing).
                    var taken = BoardSceneryLayouts.takenTokens(hex);
                    for (int type : ART) {
                        if (hex.containsTerrain(type) && !server(hex, type) && !taken.containsKey(type)) {
                            untaken.merge(hex.getTerrain(type).toString(), 1, Integer::sum);
                        }
                    }
                    var loadedDesigns = appearances.get(new Coords(x, y));
                    hex.getAppearance().forEach((owner, design) -> {
                        if (!owner.equals("bridge") && design.variant() != null && !design.equals(loadedDesigns.get(owner))) {
                            designs.merge(designFamily(design.variant()), 1, Integer::sum);
                        }
                    });
                    var route = RailRoute.find(hex, RailRoute.Kind.MAGLEV);
                    if (route != null) {
                        markers++;
                        assertNull(route.group(), "A route marker is never a group member");
                        if (route.pillars()) { pillaredMaglevs++; }
                        if (route.placement().equals(BoardDecoration.Placement.on("ground", 1))) {
                            raisedMaglevs++;
                            assertTrue(route.pillars(), file.getFileName() + " " + x + "," + y + ": a lifted maglev stands on Pillars");
                        }
                        if (route.placement().mode().equals("absolute")) { absolute++; }
                        // Other artwork of the hex (road trees, say) keeps its own support.
                        var pieces = hex.getDecorations().stream().filter(object -> !RailRoute.isRoute(object)
                              && object.asset().startsWith("scenery/maglev/")).toList();
                        assertTrue(pieces.stream().allMatch(object -> object.placement().equals(route.placement())),
                              file.getFileName() + " " + x + "," + y + ": the pieces ride the route");
                        double level = route.placement().level(hex);
                        boolean climbs = false;
                        for (int direction = 0; direction < 6; direction++) {
                            Hex next = board.getHex(new Coords(x, y).translated(direction));
                            var other = RailRoute.find(next, RailRoute.Kind.MAGLEV);
                            if (other == null) { continue; }
                            if (!RailRoute.joined(route, direction, other)) { apart++; continue; }
                            joinedSides++;
                            climbs |= other.placement().level(next) != level;
                        }
                        if (!pieces.isEmpty()) { stations++; if (climbs) { sloped++; } }
                    }
                    var rail = RailRoute.find(hex, RailRoute.Kind.RAIL);
                    if (rail != null) {
                        rails++;
                        assertNull(rail.group(), "A rail marker is never a group member");
                        if (RailRoute.onDeck(rail)) { deckRails++; }
                        // Every legacy rail sprite lays its sides' default gentle pieces: no stored tracks.
                        if (rail.tracks() != 0) { storedTracks++; }
                    }
                    for (BoardDecoration object : hex.getDecorations()) {
                        objects++;
                        if (object.group() != null) { groups.add(object.group()); }
                        perAsset.merge(object.asset(), 1, Integer::sum);
                        String family = family(object);
                        if (family != null) { families.merge(family, 1, Integer::sum); }
                        if (!object.stretch().equals(BoardDecoration.Stretch.NONE)) { stretched++; }
                        if (object.asset().startsWith("decal/emblems/")) {
                            if (object.scale() == 1) { emblemSets++; } else { emblemSingles++; }
                        }
                        if (object.asset().startsWith("decal/car-park/")) {
                            if (object.mirror()) { foldedRows++; } else { besideRows++; }
                        }
                        receivers.merge(object.placement().receiver() == null ? "absolute"
                              : object.placement().receiver().terrain(), 1, Integer::sum);
                        // The trees of a road passing under a bridge stand beneath its deck.
                        assertFalse(BoardTreeDistribution.PARK.contains(object.asset()) && object.placement().receiver() != null
                              && object.placement().receiver().terrain().equals("bridge"), file.getFileName() + ": a tree on a deck");
                        if (checkedAssets.add(object.asset())) {
                            // Props have a mesh; decals (emblems, car parks) their image by path convention; a route
                            // marker's track (rail or maglev) is generated by the route builder.
                            assertTrue(object.kind().equals("decal") ? BoardDecalArt.image(object.asset()).isFile()
                                  : RailRoute.isRoute(object)
                                  || Files.isRegularFile(models.resolve(object.asset() + ".glb")), object.asset());
                            assertTrue(blueprint.asset(object.asset()) != null, "Unlabelled import " + object.asset());
                        }
                    }
                }
            }
            var written = new ByteArrayOutputStream();
            BoardFile.write(board, written);
            // The native flag means converted: a second conversion decodes nothing and changes nothing.
            assertEquals(List.of(), BoardSceneryLayouts.convert(board), "Converted again: " + file);
            var again = new ByteArrayOutputStream();
            BoardFile.write(board, again);
            assertEquals(written.toString(StandardCharsets.UTF_8), again.toString(StandardCharsets.UTF_8),
                  "Converting twice is converting once: " + file);
            // K2: the .board2 text is the board without its taken tokens, and reads back as exactly that.
            if (after.isEmpty()) {
                Board reread = BoardFile.readNative(written.toString(StandardCharsets.UTF_8));
                var reencoded = new ByteArrayOutputStream();
                BoardFile.write(reread, reencoded);
                assertEquals(written.toString(StandardCharsets.UTF_8), reencoded.toString(StandardCharsets.UTF_8),
                      "Saved connection topology survives reload: " + file);
                for (int y = 0; y < board.getHeight(); y++) {
                    for (int x = 0; x < board.getWidth(); x++) {
                        var stored = reread.getHex(x, y);
                        assertTrue(BoardSceneryLayouts.withoutTakenTokens(stored) == stored,
                              file + " " + x + "," + y + ": the .board2 holds no taken token");
                        // Batch 10: nor any legacy art token at all (server rubble values are game state).
                        for (int type : ART) {
                            assertFalse(stored.containsTerrain(type) && !server(stored, type),
                                  file + " " + x + "," + y + ": the .board2 holds " + stored.getTerrain(type));
                        }
                    }
                }
            } else {
                // Synthetic coverage fixtures and some old maps already contain illegal connections.
                // Conversion preserves those errors; the native reader is intentionally strict.
                assertThrows(java.io.IOException.class,
                      () -> BoardFile.readNative(written.toString(StandardCharsets.UTF_8)),
                      "Native validation still rejects the preserved rule errors: " + file);
            }
            results.add(new Result(Configuration.boardsDir().toPath().relativize(file).toString(), objects, groups.size(), millis));
        }
        System.out.printf("CORPUS headless JVM: %b (the conversion reads no image either way)%n",
              java.awt.GraphicsEnvironment.isHeadless());
        System.out.printf("CORPUS boards=%d objects=%d groups=%d%n", results.size(),
              results.stream().mapToInt(Result::objects).sum(), results.stream().mapToInt(Result::groups).sum());
        results.stream().sorted((a, b) -> Integer.compare(b.objects(), a.objects())).limit(10)
              .forEach(r -> System.out.printf("CORPUS board %6d objects %5d groups %6d ms  %s%n", r.objects(), r.groups(), r.millis(), r.board()));
        perAsset.forEach((asset, count) -> System.out.printf("CORPUS asset %7d  %s%n", count, asset));
        untaken.forEach((token, count) -> System.out.printf("CORPUS untaken %6d hexes  %s%n", count, token));
        assertEquals(Map.of(), untaken, "Batch 10: the conversion takes every legacy art token");
        designs.forEach((design, count) -> System.out.printf("CORPUS design %7d hexes  %s%n", count, design));
        families.forEach((family, count) -> System.out.printf("CORPUS batch-10 family %7d  %s%n", count, family));
        // Batch 12: the seaport yards and cranes are the container yard looks (rows, and the crane looks by boom side).
        var nonBuildingDesigns = new TreeMap<>(designs);
        List.of("building/lunar-habitat", "building/glass-dome", "building/biodome").forEach(nonBuildingDesigns::remove);
        assertEquals(new TreeMap<>(Map.ofEntries(Map.entry("ground/blend/*", 6_705),
              Map.entry("ground/quay-*", 66), Map.entry("road/horizontal", 1_760), Map.entry("road/rounded", 19_749),
              Map.entry("rough/felled-trees", 967), Map.entry("rough/obstacles", 1_314),
              Map.entry("woods/container-yard/quay-crane", 193), Map.entry("woods/container-yard/quay-crane-mirrored", 212),
              Map.entry("woods/container-yard/rows", 3_819), Map.entry("woods/orchard", 4_291))), nonBuildingDesigns);
        // The 105 crane tips: the 61 a converted crane's boom reaches place nothing, the others are boom segments.
        assertEquals(44, perAsset.get("scenery/seaport/yard/crane-boom"));
        assertTrue(perAsset.keySet().stream().noneMatch(asset -> asset.startsWith("scenery/seaport/")
              && !asset.equals("scenery/seaport/yard/crane-boom")), "No legacy seaport object: " + perAsset.keySet());
        assertEquals(new TreeMap<>(Map.ofEntries(Map.entry("decal/landing-pads clipped", 503),
              Map.entry("decal/landing-pads spanning", 85), Map.entry("decal/landing-pads/heli-pad", 978),
              Map.entry("decal/lettering", 327), Map.entry("decal/lettering painted", 68),
              Map.entry("decal/runway", 4_525), Map.entry("decal/sport", 142),
              Map.entry("scenery/rail/crossing-signal", 16), Map.entry("scenery/roofs/landing-light", 4_443))), families);
        System.out.printf("CORPUS rail markers=%d (on a bridge deck %d, storing tracks %d)%n", rails, deckRails, storedTracks);
        // Only rails with a side along their bridge ride its deck; the four that cross under one lie beneath it. 105 of
        // the 137 are the rail a rail bridge carries across its span (batch-10 answer 3), on 11 boards.
        assertEquals(137, deckRails);
        assertEquals(0, storedTracks);
        // b13 ways: the 12,044 legacy rails are terrain, no stored marker.
        assertEquals(11_907, railTerrain);
        assertEquals(137, deckRailTerrain);
        receivers.forEach((receiver, count) -> System.out.printf("CORPUS receiver %7d objects  %s%n", count, receiver));
        System.out.printf("CORPUS woods drawn without trees (a container yard look): %d hexes%n", noTrees);
        // The shipped boards hold 23 complete 7-hex emblem sets and 226 one-hex variants (none incomplete).
        System.out.printf("CORPUS emblems: %d sets as one decal each, %d one-hex variants%n", emblemSets, emblemSingles);
        assertEquals(23, emblemSets);
        assertEquals(226, emblemSingles);
        System.out.printf("CORPUS car-park roads: %d lane roads on %d boards %s, %d car parks off the ground without a road, "
              + "%d car parks keep an authored road, %d neighbour roads written out%n", laneRoads.values().stream()
              .mapToInt(Integer::intValue).sum(), laneBoards.size(), laneRoads, offGroundParks, authoredParks, keptNeighbours);
        System.out.printf("CORPUS car-park rows: %d beside their road, %d back to back (mirrored)%n", besideRows, foldedRows);
        System.out.printf("CORPUS stretched objects (narrow glass roofs): %d%n", stretched);
        // The shipped boards have pillar art on 491 bridge hexes, on 15 boards.
        System.out.printf("CORPUS bridge hexes with their Pillars toggle on: %d%n", pillaredBridges);
        assertEquals(491, pillaredBridges);
        System.out.printf("CORPUS maglev markers=%d (absolute %d), joined sides=%d, adjacent markers apart=%d sides, "
              + "station/train hexes=%d, of which on a climbing route=%d%n", markers, absolute, joinedSides, apart, stations, sloped);
        // Runs a level up stand on Pillars (on 18 boards); the row of pillar art along Karkand's line lifts it.
        System.out.printf("CORPUS maglev markers on Pillars=%d, lifted by a row of pillar art=%d%n", pillaredMaglevs, raisedMaglevs);
        assertEquals(632, pillaredMaglevs);
        assertEquals(18, raisedMaglevs);
        results.stream().filter(r -> r.board().contains("Valencia_City"))
              .forEach(r -> System.out.printf("CORPUS valencia conversion %d ms, %d objects, %d groups%n", r.millis(), r.objects(), r.groups()));
    }

    /** The terrains that are legacy art and nothing else. */
    private static final int[] ART = { Terrains.FLUFF, Terrains.ROAD_FLUFF, Terrains.GROUND_FLUFF, Terrains.WATER_FLUFF,
          Terrains.BLDG_FLUFF };

    /** The hex's legacy art terrains (server values included), as text. */
    private static List<String> tokens(Hex hex) {
        List<String> result = new ArrayList<>();
        for (int type : ART) {
            if (hex.containsTerrain(type)) { result.add(hex.getTerrain(type).toString()); }
        }
        return result;
    }

    private static Set<String> validationErrors(List<String> lines) {
        Set<String> errors = new HashSet<>();
        String hex = "board";
        for (String line : lines) {
            if (line.startsWith("Errors in hex ")) { hex = line; }
            else { errors.add(hex + " " + line); }
        }
        return errors;
    }

    /** FLUFF and GROUND_FLUFF from 2000 up: cleared-rubble state written in play, never legacy art. */
    private static boolean server(Hex hex, int type) {
        return (type == Terrains.FLUFF || type == Terrains.GROUND_FLUFF) && hex.terrainLevel(type) >= 2000;
    }

    /** A design's family */
    private static String designFamily(String variant) {
        if (variant.startsWith("ground/quay-")) { return "ground/quay-*"; }
        return variant.startsWith("ground/blend/") ? "ground/blend/*" : variant;
    }

    /** A batch-10 object's family (rails, airfield, lettering, courts), or null. */
    private static String family(BoardDecoration object) {
        String asset = object.asset();
        if (asset.startsWith("scenery/rail/") || asset.startsWith("decal/rail/") || asset.equals("scenery/roofs/landing-light")
              || asset.equals("decal/landing-pads/heli-pad")) { return asset; }
        if (asset.startsWith("decal/landing-pads/")) { return "decal/landing-pads " + (object.clipToHex() ? "clipped" : "spanning"); }
        if (asset.startsWith("decal/lettering/")) { return "decal/lettering" + (object.colours().slots().isEmpty() ? "" : " painted"); }
        return asset.startsWith("decal/runway/") || asset.startsWith("decal/sport/") ? asset.substring(0, asset.lastIndexOf('/')) : null;
    }
}
