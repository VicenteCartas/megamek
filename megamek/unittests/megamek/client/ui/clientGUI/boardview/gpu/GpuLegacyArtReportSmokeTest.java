/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.tileset.HexTileset;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import megamek.common.board.TilesetMatcher;
import megamek.common.game.Game;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Batch 10's conversion report (b10-design section 6, the user's deliverable): every converted legacy art item as this
 * build draws it, beside its legacy 2D sprite. One legacy fixture per family (runway, pads, lettering, courts, elevator
 * plates, gravel, rails and level crossings, legacy maglev, and the designs found on the shipped boards: building art,
 * transitions, kerbs, orchards, rough, quay), converted as every load site converts it ({@link BoardSceneryLayouts#convert}).
 * Per item: the game's 2D tileset drawing the loaded {@code .board} ({@code <item>-2d.png}), for designs also the saved
 * {@code .board2} through the 2D hook ({@code <item>-2d-board2.png}), and the 3D board's capture of the same cell from
 * above ({@code <item>-3d.png}) and, where the result is a model, at an angle ({@code <item>-3d-oblique.png}). The
 * junction layouts, the rolling stock and the maglev pillars are drawn natively (no legacy art), the rolling stock
 * stamped and placed through the editor session so it rides its track. {@code manifest.tsv} lists every item's tokens,
 * legacy images, outcome, objects and designs for the report's tables. Captures go to
 * {@code build/gpu-board-review/b10-report/<family>/}.
 */
@Tag("on-demand")
class GpuLegacyArtReportSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
          "b10-report");
    /** The longer side of a capture window, in pixels. */
    private static final int WINDOW = 560;
    /** An angled shot's zoom against its cell's shot from above: closer, so models show their detail. */
    private static final float OBLIQUE = .5f;
    /** The 2D board's hex: 84 x 72 pixels, columns 63 apart, odd columns half a hex lower. */
    private static final int HEX_W = 84, HEX_H = 72, COLUMN = 63;
    private static final int[] ART = { Terrains.FLUFF, Terrains.ROAD_FLUFF, Terrains.GROUND_FLUFF, Terrains.WATER_FLUFF,
          Terrains.BLDG_FLUFF };
    /** A dropship set's pieces 1-7 (fluff:43): the direction of each from the centre piece 4 (-1). */
    private static final int[] PIECE_DIRECTION = { 0, 5, 1, -1, 4, 2, 3 };

    /** One report item: its hexes (the first is its centre) and the cell both views frame, by its north-west hex. */
    private record Item(String name, String source, List<Coords> hexes, Coords corner) { }

    /** A capture: the camera's focus, zoom (world units per pixel) and tilt from the vertical. */
    private record Shot(Vector3 focus, float zoom, float tilt) { }

    /** A family: its board and items in cells of {@code cols} x {@code rows} hexes. */
    private static final class Family {
        final String name;
        final Board board;
        final int cols, rows, perRow;
        final boolean oblique, legacy, designs;
        final List<Item> items = new ArrayList<>();

        /** A legacy fixture of {@code count} cells, {@code perRow} to a row, every hex {@code ground}. */
        Family(String name, int count, int cols, int rows, int perRow, String ground, boolean oblique, boolean designs) {
            this(name, Board.createEmptyBoard(perRow * cols, (count + perRow - 1) / perRow * rows), cols, rows, perRow,
                  oblique, true, designs);
            for (int x = 0; x < board.getWidth(); x++) {
                for (int y = 0; y < board.getHeight(); y++) { board.setHex(new Coords(x, y), new Hex(0, ground, "")); }
            }
        }

        Family(String name, Board board, int cols, int rows, int perRow, boolean oblique, boolean legacy, boolean designs) {
            this.name = name;
            this.board = board;
            this.cols = cols;
            this.rows = rows;
            this.perRow = perRow;
            this.oblique = oblique;
            this.legacy = legacy;
            this.designs = designs;
        }

        /** The next cell's north-west hex. */
        Coords corner() { return new Coords(items.size() % perRow * cols, items.size() / perRow * rows); }

        /** The next cell's centre hex (for odd cells; one in from the corner for 3 x 3). */
        Coords centre() { return new Coords(corner().getX() + cols / 2, corner().getY() + rows / 2); }

        /** Places {@code hexes} (the first is the item's centre) in the next cell; all of them are the item. */
        void add(String name, String source, Map<Coords, Hex> hexes) {
            add(name, source, hexes, false);
        }

        /** Places {@code hexes} in the next cell; the item is its centre alone (a shipped hex with its neighbours). */
        void add(String name, String source, Map<Coords, Hex> hexes, boolean centreOnly) {
            Coords corner = corner();
            hexes.forEach(board::setHex);
            List<Coords> item = List.copyOf(hexes.keySet());
            items.add(new Item(name, source, centreOnly ? item.subList(0, 1) : item, corner));
        }

        /** One hex of terrain at the next cell's centre. */
        void single(String name, String terrain) {
            add(name, "", Map.of(centre(), new Hex(0, terrain, "")));
        }

        /** An item framed around hexes already on the board (the first is its centre). */
        void around(String name, List<Coords> hexes) {
            Coords centre = hexes.getFirst();
            items.add(new Item(name, "", hexes, new Coords(centre.getX() - cols / 2, centre.getY() - rows / 2)));
        }

        /** The capture window: the cell's 2D extent scaled to {@link #WINDOW} on its longer side. */
        int[] window() {
            int width = (cols - 1) * COLUMN + HEX_W, height = rows * HEX_H + HEX_H / 2;
            double scale = (double) WINDOW / Math.max(width, height);
            return new int[] { (int) Math.round(width * scale), (int) Math.round(height * scale) };
        }
    }

    @Test
    void everyConversionIsDrawnBesideItsLegacySprite() throws Exception {
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        TilesetMatcher matcher = new TilesetMatcher(Configuration.hexesDir());
        matcher.load("saxarba.tileset");
        List<Family> families = new ArrayList<>(List.of(runway(), pads(), padSets(), lettering(), courts(), elevators(),
              gravel(), rails(), junctions(), maglev()));
        families.addAll(designs(matcher));
        families.addAll(rollingStock());
        // The game's 2D tileset (saxarba, the default), which draws a hex's designs as the tokens they stand for.
        Game game = new Game();
        HexTileset tileset = new HexTileset(game);
        Map<Board, BoardScene> scenes = new java.util.IdentityHashMap<>();
        try (PrintWriter manifest = new PrintWriter(Files.newBufferedWriter(OUTPUT.toPath().resolve("manifest.tsv"),
              StandardCharsets.UTF_8))) {
            tileset.loadFromFile("saxarba.tileset");
            manifest.println("family\titem\tsource\ttokens\tlegacy images\toutcome\tobjects\tdesigns\tcaptures");
            for (Family family : families) {
                File folder = new File(OUTPUT, family.name);
                assertTrue(folder.isDirectory() || folder.mkdirs());
                Board loaded = family.legacy ? copy(family.board) : null;
                Board saved = null;
                if (family.legacy && !scenes.containsKey(family.board)) {
                    assertEquals(List.of(), BoardSceneryLayouts.convert(family.board), family.name + " converts without issues");
                    var text = new ByteArrayOutputStream();
                    BoardFile.write(family.board, text);
                    saved = BoardFile.readNative(text.toString(StandardCharsets.UTF_8));
                }
                BoardScene scene = scenes.computeIfAbsent(family.board, GpuLegacyArtReportSmokeTest::scene);
                Map<String, Shot> shots = new LinkedHashMap<>();
                int[] window = family.window();
                for (Item item : family.items) {
                    List<String> captures = new ArrayList<>();
                    if (loaded != null) {
                        ImageIO.write(drawn2d(tileset, loaded, family, item), "png", new File(folder, item.name() + "-2d.png"));
                        captures.add("2d");
                        if (family.designs) {
                            ImageIO.write(drawn2d(tileset, saved, family, item), "png", new File(folder, item.name() + "-2d-board2.png"));
                            captures.add("2d-board2");
                        }
                    }
                    shots.put(item.name() + "-3d", top(family, item, window));
                    captures.add("3d");
                    if (family.oblique) {
                        float level = family.board.getHex(item.hexes().getFirst()).getLevel();
                        shots.put(item.name() + "-3d-oblique", new Shot(BoardGeometry.center(item.hexes().getFirst(), level),
                              top(family, item, window).zoom() * OBLIQUE, 55));
                        captures.add("3d-oblique");
                    }
                    manifest.println(String.join("\t", family.name, item.name(), item.source(), tokens(loaded, item),
                          images(tileset, loaded, item), outcome(family.board, item, loaded), objects(family.board, scene, item),
                          designs(family.board, item), String.join(",", captures)));
                    if (saved != null) {
                        // A .board2 holds no legacy art token: what stands for it are the objects and designs above.
                        for (Coords at : item.hexes()) {
                            for (int type : ART) {
                                assertFalse(saved.getHex(at).containsTerrain(type) && saved.getHex(at).terrainLevel(type) < 2000,
                                      family.name + " " + item.name() + ": the .board2 holds " + saved.getHex(at).getTerrain(type));
                            }
                        }
                    }
                }
                manifest.flush();
                render(folder, window, scene, shots);
            }
        } finally {
            tileset.close();
        }
    }

    // ----------------------------------------------------------------------------------------------- legacy fixtures

    /** Runway markings (FLUFF 14): centre lines, thresholds and the 24 designations, on pavement. */
    private static Family runway() {
        Family family = new Family("runway", 30, 3, 3, 10, "pavement:1", false, false);
        for (int value = 0; value < 30; value++) { family.single(token(14, value), "pavement:1;fluff:14:" + value); }
        return family;
    }

    /** One-hex landing pads (FLUFF 40-42) and the heli pads (FLUFF 2:0-2), their lights GLBs. */
    private static Family pads() {
        Family family = new Family("pads", 27, 3, 3, 9, "pavement:1", true, false);
        for (int[] range : new int[][] { { 40, 6 }, { 41, 6 }, { 42, 12 } }) {
            for (int value = 1; value <= range[1]; value++) {
                family.single(token(range[0], value), "pavement:1;fluff:" + range[0] + ":" + value);
            }
        }
        for (int value = 0; value < 3; value++) { family.single(token(2, value), "pavement:1;fluff:2:" + value); }
        return family;
    }

    /** The four 7-hex DropShip pads (FLUFF 43), and two incomplete sets: a lone centre and a ring without its centre. */
    private static Family padSets() {
        Family family = new Family("pad-sets", 6, 5, 5, 3, "pavement:1", false, false);
        String[] names = { "dropship-concrete", "dropship-asphalt", "dropship-ring-concrete", "dropship-ring-asphalt" };
        for (int set = 0; set < 4; set++) { family.add(names[set], "", set(family.centre(), set, 1, 2, 3, 4, 5, 6, 7)); }
        family.add("dropship-concrete-lone-centre", "", set(family.centre(), 0, 4));
        family.add("dropship-ring-asphalt-without-centre", "", set(family.centre(), 3, 1, 2, 3, 5, 6, 7));
        return family;
    }

    /** The pieces of DropShip set {@code set} around {@code centre}; the first hex is the centre, pad or not. */
    private static Map<Coords, Hex> set(Coords centre, int set, int... pieces) {
        Map<Coords, Hex> hexes = new LinkedHashMap<>();
        hexes.put(centre, new Hex(0, "pavement:1", ""));
        for (int piece : pieces) {
            int direction = PIECE_DIRECTION[piece - 1];
            hexes.put(direction < 0 ? centre : centre.translated(direction),
                  new Hex(0, "pavement:1;fluff:43:" + (set * 7 + piece), ""));
        }
        return hexes;
    }

    /** Every letter and digit (FLUFF 70-73): black upright, -30 and +30 in the first row, white below. */
    private static Family lettering() {
        Family family = new Family("lettering", 36, 7, 4, 6, "pavement:1", false, false);
        for (int glyph = 0; glyph < 36; glyph++) {
            boolean letter = glyph < 26;
            int code = letter ? glyph + 1 : glyph - 25, step = letter ? 26 : 10, black = letter ? 70 : 72;
            String name = letter ? "letter-" + (char) ('a' + glyph) : "digit-" + (code % 10);
            Coords corner = family.corner();
            Map<Coords, Hex> hexes = new LinkedHashMap<>();
            for (int colour = 0; colour < 2; colour++) {
                for (int turn = 0; turn < 3; turn++) {
                    hexes.put(new Coords(corner.getX() + 1 + 2 * turn, corner.getY() + 1 + colour),
                          new Hex(0, "pavement:1;fluff:" + (black + colour) + ":" + (code + turn * step), ""));
                }
            }
            family.add(name, "", hexes);
        }
        return family;
    }

    /** Tennis and basketball courts (FLUFF 91), on grass. */
    private static Family courts() {
        Family family = new Family("courts", 12, 3, 3, 6, "", false, false);
        for (int value = 1; value <= 12; value++) { family.single(token(91, value), "fluff:91:" + value); }
        return family;
    }

    /** Solaris elevators at levels -10 to 10: their plates are decals of the rule terrain. */
    private static Family elevators() {
        Family family = new Family("elevators", 21, 3, 3, 7, "pavement:1", false, false);
        for (int level = -10; level <= 10; level++) {
            family.single("solaris_elevator-" + level, "pavement:1;solaris_elevator:" + level);
        }
        return family;
    }

    /** Cleared rubble (server state): the gravel decal row, alone and under a light rubble path. */
    private static Family gravel() {
        Family family = new Family("gravel", 2, 3, 3, 2, "", false, false);
        family.single("ground_fluff-2000", "ground_fluff:2000");
        family.single("ground_fluff-2000-fluff-2001", "ground_fluff:2000;fluff:2001");
        return family;
    }

    /**
     * Every legacy rail sprite (FLUFF 10): the 64 side masks one hex each on grass (rail00 alone places nothing), a
     * line of three rail00 hexes that join, and the six level crossings on their roads. Oblique shots show the buffer
     * stops of dead ends and stranded sides.
     */
    private static Family rails() {
        Family family = new Family("rails", 71, 3, 3, 12, "", true, false);
        for (int mask = 0; mask < 64; mask++) { family.single(String.format("rail%02d", mask), "fluff:10:" + mask); }
        Coords centre = family.centre();
        Map<Coords, Hex> line = new LinkedHashMap<>();
        for (Coords at : List.of(centre, centre.translated(0), centre.translated(3))) { line.put(at, new Hex(0, "fluff:10:0", "")); }
        family.add("rail00-line", "", line);
        // The crossings' roads, as the shipped Wagon Wheel boards and the decal's turn give them.
        int[] roads = { 36, 18, 9, 9, 18, 36 };
        for (int crossing = 0; crossing < 6; crossing++) {
            family.single(String.format("rail_road%02d", crossing + 1), "road:1:" + roads[crossing] + ";fluff:10:" + (64 + crossing));
        }
        return family;
    }

    /** The junction card's layouts: every choice mask's tracks, drawn as stored (native, no legacy art). */
    private static Family junctions() {
        List<int[]> layouts = new ArrayList<>();
        for (int mask = 0; mask < 64; mask++) {
            var variants = RailRoute.variants(mask);
            for (int tracks : variants.size() > 1 ? variants : List.<Integer>of()) { layouts.add(new int[] { mask, tracks }); }
        }
        Board board = Board.createEmptyBoard(10 * 3, (layouts.size() + 9) / 10 * 3);
        Family family = new Family("junctions", board, 3, 3, 10, true, false, false);
        int index = 0;
        for (int[] layout : layouts) {
            var marker = new BoardDecoration("j" + index++, "prop", RailRoute.Kind.RAIL.asset, null, 0, 0, 0, false, 1,
                  BoardDecoration.Placement.ground(), 0).withConnections(layout[0]).withTracks(layout[1]);
            Hex hex = new Hex(0);
            hex.setDecorations(List.of(marker));
            int variant = RailRoute.variants(layout[0]).indexOf(layout[1]) + 1;
            family.add(String.format("rail%02d-layout-%d", layout[0], variant), "", Map.of(family.centre(), hex));
        }
        board.setNativeFormat(true);
        return family;
    }

    /** The twelve legacy maglev sprites. */
    private static Family maglev() {
        Family family = new Family("maglev", 12, 3, 3, 6, "", true, false);
        for (int value = 0; value < 12; value++) { family.single(token(9, value), "fluff:9:" + value); }
        return family;
    }

    // ---------------------------------------------------------------------------------------- designs (shipped hexes)

    /** A shipped hex whose art token a design took, with its six neighbours (the centre first). */
    private record Crop(String source, Hex[] hexes) { }

    /**
     * The design families from the shipped boards: per legacy image the first hex that shows it (kerbs, horizontal
     * road pieces, orchards, rough, the building models chosen beside art, quay), and per design the first hex of its
     * most used image (the 55 building art designs, the transition families and strengths).
     */
    private static List<Family> designs(TilesetMatcher matcher) throws IOException {
        Path root = Configuration.boardsDir().toPath();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) { files = walk.filter(path -> path.toString().endsWith(".board")).sorted().toList(); }
        // family -> key -> image -> [count]; and the first crop of each family/key/image
        Map<String, Map<String, Map<String, int[]>>> counts = new TreeMap<>();
        Map<String, Crop> first = new LinkedHashMap<>();
        for (Path file : files) {
            if (!Files.readString(file, StandardCharsets.ISO_8859_1).contains("fluff:")) { continue; }
            final Board board;
            try { board = BoardFile.read(file); }
            catch (IOException | IllegalArgumentException unreadable) { continue; }
            for (int row = 0; row < board.getHeight(); row++) {
                for (int column = 0; column < board.getWidth(); column++) {
                    final int x = column, y = row;
                    Hex hex = board.getHex(x, y);
                    var taken = BoardSceneryLayouts.takenTokens(hex);
                    if (!taken.containsValue(BoardSceneryLayouts.Taken.DESIGN)) { continue; }
                    // The designs the conversion gives the tokens (withoutTakenTokens adds none).
                    Hex designed = hex.duplicate();
                    BoardSceneryLayouts.designTypedTokens(hex, designed);
                    Map<String, HexAppearance> added = new TreeMap<>(designed.getAppearance());
                    added.keySet().removeAll(hex.getAppearance().keySet());
                    var supers = matcher.select(hex).supers();
                    for (var token : taken.entrySet()) {
                        if (token.getValue() != BoardSceneryLayouts.Taken.DESIGN) { continue; }
                        int type = token.getKey();
                        String image = supers.stream().filter(pick -> pick.has(type)).map(TilesetMatcher.Pick::source)
                              .findFirst().orElse("(no image)");
                        String[] familyKey = designFamily(type, added, image);
                        counts.computeIfAbsent(familyKey[0], k -> new TreeMap<>()).computeIfAbsent(familyKey[1], k -> new TreeMap<>())
                              .computeIfAbsent(image, k -> new int[1])[0]++;
                        first.computeIfAbsent(familyKey[0] + "|" + familyKey[1] + "|" + image, k -> crop(root, file, board, x, y));
                    }
                }
            }
        }
        List<Family> result = new ArrayList<>();
        for (var family : counts.entrySet()) {
            boolean oblique = !family.getKey().equals("transitions") && !family.getKey().contains("roads")
                  && !family.getKey().equals("kerbs");
            Family fixture = new Family(family.getKey(), family.getValue().size(), 5, 5, 8, "", oblique, true);
            Set<String> names = new java.util.HashSet<>();
            for (var key : family.getValue().entrySet()) {
                // The key's most used image that draws something (per-image families have one).
                String image = key.getValue().entrySet().stream()
                      .max(java.util.Comparator.<Map.Entry<String, int[]>, Boolean>comparing(e -> !e.getKey().endsWith("misc/blank.png"))
                            .thenComparingInt(e -> e.getValue()[0]))
                      .orElseThrow().getKey();
                Crop crop = first.get(family.getKey() + "|" + key.getKey() + "|" + image);
                Coords centre = fixture.centre();
                Map<Coords, Hex> hexes = new LinkedHashMap<>();
                hexes.put(centre, crop.hexes()[0]);
                for (int direction = 0; direction < 6; direction++) { hexes.put(centre.translated(direction), crop.hexes()[direction + 1]); }
                String name = key.getKey().replaceAll("^.*/", "").replaceAll("\\.(png|gif)$", "").replaceAll("[^A-Za-z0-9_-]+", "-");
                String unique = name;
                for (int n = 2; names.contains(unique); n++) { unique = name + "-" + n; }
                names.add(unique);
                fixture.add(unique, crop.source() + " (" + key.getValue().get(image)[0] + " hexes show " + image + ")", hexes, true);
            }
            result.add(fixture);
        }
        return result;
    }

    /** The report family and item key of a token a design took: per image, or per design for building art and transitions. */
    private static String[] designFamily(int type, Map<String, HexAppearance> added, String image) {
        if (type == Terrains.GROUND_FLUFF) {
            var design = added.get("ground");
            return new String[] { "transitions", design.variant() + "-" + Math.round(design.strength() * 6) + "of6" };
        }
        if (type == Terrains.BLDG_FLUFF) { return new String[] { "building-art", added.get("building").variant() }; }
        if (type == Terrains.ROAD_FLUFF) {
            String variant = added.get("road").variant();
            return new String[] { variant.equals("road/rounded") ? "kerbs" : variant.equals("road/horizontal")
                  ? "horizontal-roads" : "dirt-roads", image };
        }
        String family = added.containsKey("vegetation") ? "orchards" : added.containsKey("rough") ? "rough"
              : added.containsKey("building") ? "building-models" : "quay";
        return new String[] { family, image };
    }

    /** The hex at x, y with its six neighbours (an off-board neighbour: a plain hex at the centre's level). */
    private static Crop crop(Path root, Path file, Board board, int x, int y) {
        Coords centre = new Coords(x, y);
        Hex[] hexes = new Hex[7];
        hexes[0] = board.getHex(centre).duplicate();
        for (int direction = 0; direction < 6; direction++) {
            Hex next = board.getHex(centre.translated(direction));
            hexes[direction + 1] = next != null ? next.duplicate() : new Hex(hexes[0].getLevel());
        }
        return new Crop(root.relativize(file).toString().replace('\\', '/') + " " + String.format("%02d%02d", x + 1, y + 1), hexes);
    }

    // ------------------------------------------------------------------------------ rolling stock and pillars (native)

    /**
     * The rolling stock and maglev pillars, drawn through the editor session as a user draws them: rail and maglev lines
     * (maglev one level up), the five train stamps on them, every vehicle placed alone on a line (each snaps onto the
     * track), and two maglevs two levels up over a river, one with its Pillars on.
     */
    private static List<Family> rollingStock() throws Exception {
        String[] stamps = { "stamp/rail-train", "stamp/express-train", "stamp/colony-train", "stamp/maglev-train",
              "stamp/maglev-colony-train" };
        String[][] singles = {
              { "scenery/rail/locomotive", "scenery/rail/coach", "scenery/rail/box-car", "scenery/rail/tank-car",
                    "scenery/rail/ore-hopper", "scenery/rail/heavy-flatbed" },
              { "scenery/rail/express-power-car", "scenery/rail/express-car", "scenery/rail/colony-locomotive",
                    "scenery/rail/container-wagon", "scenery/rail/heavy-hopper", "scenery/rail/insulated-tanker",
                    "scenery/rail/equipment-carrier" },
              { "scenery/maglev/cab", "scenery/maglev/wagon", "scenery/maglev/service-car", "scenery/maglev/city-freight",
                    "scenery/maglev/platform" },
              { "scenery/maglev/colony-cab", "scenery/maglev/colony-cargo", "scenery/maglev/colony-tanker",
                    "scenery/maglev/colony-containers", "scenery/maglev/colony-heavy" } };
        // Lines N-S, three columns apart: 0-2 rail (train stamps), 3-4 maglev (maglev stamps), 5-6 rail and 7-8 maglev
        // (single vehicles), 10 and 11 maglevs two levels up over a river, the first with its Pillars on.
        int height = 16;
        int[] lines = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 11 };
        Set<Integer> maglevs = Set.of(3, 4, 7, 8, 10, 11);
        var built = new FutureTask<Board>(() -> {
            var session = new BoardEditorSession();
            Board board = Board.createEmptyBoard(36, height);
            for (int y = 6; y <= 8; y++) {
                for (int line : new int[] { 10, 11 }) { board.setHex(new Coords(column(line), y), new Hex(0, "water:1", "")); }
            }
            session.game().setBoard(board);
            for (int line : lines) {
                boolean maglev = maglevs.contains(line);
                session.command(new Command(Action.ASSET, "", (maglev ? RailRoute.Kind.MAGLEV : RailRoute.Kind.RAIL).asset), null);
                if (maglev) { session.command(new Command(Action.BRUSH_VALUE, "wayOffset", line >= 10 ? "2" : "1"), null); }
                GpuEditorRailSmokeTest.stroke(session, GpuEditorRailSmokeTest.run(new Coords(column(line), 0), 3, height - 1));
            }
            for (int stamp = 0; stamp < stamps.length; stamp++) {
                session.command(new Command(Action.ASSET, "", stamps[stamp]), null);
                session.pointer(new Coords(column(stamp), height / 2), 0, 0, false);
                session.finishStroke();
            }
            for (int line = 0; line < singles.length; line++) {
                for (int i = 0; i < singles[line].length; i++) {
                    session.command(new Command(Action.ASSET, "", singles[line][i]), null);
                    session.pointer(new Coords(column(5 + line), 1 + 2 * i), 0, 0, false);
                    session.finishStroke();
                }
            }
            session.command(new Command(Action.TOOL, "", "SELECT"), null);
            Coords pillared = new Coords(column(10), 2);
            session.pointer(pillared, 0, 0, false, RailRoute.find(board.getHex(pillared), RailRoute.Kind.MAGLEV).id());
            session.finishStroke();
            session.release();
            session.command(new Command(Action.PILLARS, "", "true"), null);
            return session.board();
        });
        SwingUtilities.invokeAndWait(built);
        Board board = built.get();
        Family trains = new Family("rolling-stock", board, 3, 5, 1, true, false, false);
        String[] names = { "freight-train", "express-train", "colony-train", "city-maglev", "colony-maglev" };
        for (int stamp = 0; stamp < names.length; stamp++) {
            Coords at = new Coords(column(stamp), height / 2);
            assertTrue(board.getHex(at).getDecorations().size() > 1, names[stamp] + " stamped onto its track");
            trains.around(names[stamp], List.of(at));
        }
        Family vehicles = new Family("rolling-stock-vehicles", board, 3, 3, 1, true, false, false);
        for (int line = 0; line < singles.length; line++) {
            for (int i = 0; i < singles[line].length; i++) {
                Coords at = new Coords(column(5 + line), 1 + 2 * i);
                assertTrue(board.getHex(at).getDecorations().size() > 1, singles[line][i] + " placed on its track");
                vehicles.around(singles[line][i].replaceAll("^.*/", line >= 2 ? "maglev-" : ""), List.of(at));
            }
        }
        Family pillars = new Family("maglev-pillars", board, 5, 7, 1, true, false, false);
        assertTrue(RailRoute.find(board.getHex(new Coords(column(10), 7)), RailRoute.Kind.MAGLEV).pillars(), "The route's Pillars");
        assertFalse(RailRoute.find(board.getHex(new Coords(column(11), 7)), RailRoute.Kind.MAGLEV).pillars(), "Another route's");
        pillars.around("pillars-on", List.of(new Coords(column(10), 7)));
        pillars.around("pillars-off", List.of(new Coords(column(11), 7)));
        return List.of(trains, vehicles, pillars);
    }

    /** The rolling-stock board's column of line {@code line}. */
    private static int column(int line) {
        return 1 + 3 * line;
    }

    // ------------------------------------------------------------------------------------------------ drawing

    private static BoardScene scene(Board board) {
        try { return BoardScenes.scene(board); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    /** The item's cell from straight above, framed exactly as its 2D drawing. */
    private static Shot top(Family family, Item item, int[] window) {
        // The 2D board's pixel to the 3D board's world: hex centres (42, 36) and (168, 180) are hexes (0,0) and (2,2).
        Vector3 a = BoardGeometry.center(new Coords(0, 0), 0), b = BoardGeometry.center(new Coords(2, 2), 0);
        float sx = (b.x - a.x) / (2 * COLUMN), sy = (b.y - a.y) / (2 * HEX_H);
        int[] box = box(family, item);
        float x = box[0] + box[2] / 2f, y = box[1] + box[3] / 2f;
        float level = family.board.getHex(item.hexes().getFirst()).getLevel();
        return new Shot(new Vector3(a.x + (x - HEX_W / 2f) * sx, a.y + (y - HEX_H / 2f) * sy,
              BoardGeometry.center(item.hexes().getFirst(), level).z), Math.abs(sx) * box[2] / window[0], 0);
    }

    /** The item's cell in 2D board pixels: left, top, width, height. */
    private static int[] box(Family family, Item item) {
        Coords corner = item.corner();
        return new int[] { corner.getX() * COLUMN, corner.getY() * HEX_H, (family.cols - 1) * COLUMN + HEX_W,
              family.rows * HEX_H + HEX_H / 2 };
    }

    /** The 2D tileset's drawing of the item's cell: each hex's base and superimposed images, as the 2D board stacks them. */
    private static BufferedImage drawn2d(HexTileset tileset, Board board, Family family, Item item) {
        int[] box = box(family, item);
        var image = new BufferedImage(box[2], box[3], BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(48, 48, 48));
        graphics.fillRect(0, 0, box[2], box[3]);
        Coords corner = item.corner();
        for (int x = corner.getX(); x < corner.getX() + family.cols; x++) {
            for (int y = corner.getY(); y < corner.getY() + family.rows; y++) {
                Hex hex = board.getHex(x, y);
                if (hex == null) { continue; }
                int px = x * COLUMN - box[0], py = y * HEX_H + (x % 2 == 1 ? HEX_H / 2 : 0) - box[1];
                graphics.drawImage(loaded(tileset, tileset.getBase(hex)), px, py, null);
                for (Image layer : tileset.getSupers(hex)) { graphics.drawImage(loaded(tileset, layer), px, py, null); }
            }
        }
        graphics.dispose();
        return image;
    }

    /**
     * A tileset image, decoded: its file read again (the toolkit loads an animated GIF, as the landing pads are, lazily,
     * and draws nothing without an observer), else the image once loaded.
     */
    static Image loaded(HexTileset tileset, Image image) {
        String source = tileset.imageSource(image);
        if (!source.isEmpty()) {
            try {
                BufferedImage read = ImageIO.read(new File(Configuration.hexesDir(), source));
                if (read != null) { return read; }
            } catch (IOException unreadable) {
                // drawn from the toolkit's image below
            }
        }
        return new ImageIcon(image).getImage();
    }

    private static void render(File folder, int[] window, BoardScene scene, Map<String, Shot> shots) {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        // A capture window has the cell's shape, below the application's minimum size.
        config.setWindowSizeLimits(1, 1, -1, -1);
        config.setWindowedMode(window[0], window[1]);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                var terrain = new GpuTerrain();
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene);
                    for (var shot : shots.entrySet()) {
                        camera.setIsometric(true);
                        camera.orbit((shot.getValue().tilt() < 1 ? 0 : 20) - camera.azimuth(), shot.getValue().tilt() - camera.tilt());
                        camera.center(shot.getValue().focus());
                        camera.zoom(shot.getValue().zoom() / camera.camera.zoom);
                        camera.update();
                        GpuReviewFrame.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(folder, shot.getKey() + ".png"));
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { terrain.dispose(); frame.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Report captures of " + folder.getName(), failure.get()); }
    }

    // ------------------------------------------------------------------------------------------------ manifest

    private static Board copy(Board board) {
        Board copy = Board.createEmptyBoard(board.getWidth(), board.getHeight());
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) { copy.setHex(new Coords(x, y), board.getHex(x, y).duplicate()); }
        }
        return copy;
    }

    private static String token(int level, int value) { return "fluff-" + level + "-" + value; }

    /** The legacy art tokens of the item's hexes. */
    private static String tokens(Board loaded, Item item) {
        if (loaded == null) { return "-"; }
        Set<String> result = new LinkedHashSet<>();
        for (Coords at : item.hexes()) {
            // The art terrains, and the elevator whose plate is a decal of its rule terrain.
            for (int type : new int[] { Terrains.FLUFF, Terrains.ROAD_FLUFF, Terrains.GROUND_FLUFF, Terrains.WATER_FLUFF,
                  Terrains.BLDG_FLUFF, Terrains.SOLARIS_ELEVATOR }) {
                if (loaded.getHex(at).containsTerrain(type)) { result.add(loaded.getHex(at).getTerrain(type).toString()); }
            }
        }
        return String.join(" ", result);
    }

    /** The legacy 2D images the item's hexes draw over their bases. */
    private static String images(HexTileset tileset, Board loaded, Item item) {
        if (loaded == null) { return "-"; }
        Set<String> result = new LinkedHashSet<>();
        for (Coords at : item.hexes()) {
            for (Image layer : tileset.getSupers(loaded.getHex(at))) { result.add(tileset.imageSource(layer).replace('\\', '/')); }
        }
        return String.join(" ", result);
    }

    /** What the conversion did with each token of the item's hexes ({@link BoardSceneryLayouts#takenTokens}). */
    private static String outcome(Board board, Item item, Board loaded) {
        if (loaded == null) { return "native"; }
        Set<String> result = new LinkedHashSet<>();
        for (Coords at : item.hexes()) {
            BoardSceneryLayouts.takenTokens(board.getHex(at)).forEach((type, taken) -> result.add(Terrains.getName(type) + " " + taken));
        }
        return result.isEmpty() ? "no art token (rule terrain or server state)" : String.join(" ", result);
    }

    /**
     * The objects on the item's hexes, by asset (a route with its sides and stored tracks), and what the rule terrain and
     * the routes draw without storing it ({@link BoardFeatures#terrainOwned}: elevator plates, gravel, buffer stops).
     */
    private static String objects(Board board, BoardScene scene, Item item) {
        Map<String, Integer> result = new TreeMap<>();
        for (Coords at : item.hexes()) {
            for (BoardDecoration object : board.getHex(at).getDecorations()) {
                String key = object.asset() + (RailRoute.isRoute(object) ? "[sides " + object.connections()
                      + (object.tracks() != 0 ? " tracks " + object.tracks() : "") + (object.pillars() ? " pillars" : "") + "]" : "")
                      + (object.colours().slots().isEmpty() ? "" : object.colours().slots());
                result.merge(key, 1, Integer::sum);
            }
            for (var feature : scene.tile(at).features()) {
                if (feature.decoration() != null && BoardFeatures.terrainOwned(feature.decoration())) {
                    result.merge(feature.decoration().asset() + " (drawn, not stored)", 1, Integer::sum);
                }
            }
        }
        List<String> text = new ArrayList<>();
        result.forEach((asset, count) -> text.add(asset + (count > 1 ? " x" + count : "")));
        return text.isEmpty() ? "-" : String.join(" ", text);
    }

    /** The designs of the item's centre hex (appearance owner = variant, strength). */
    private static String designs(Board board, Item item) {
        List<String> text = new ArrayList<>();
        board.getHex(item.hexes().getFirst()).getAppearance().forEach((owner, design) -> text.add(owner + "=" + design.variant()
              + (design.strength() == null ? "" : " " + Math.round(design.strength() * 6) + "/6")));
        return text.isEmpty() ? "-" : String.join(" ", text);
    }
}
