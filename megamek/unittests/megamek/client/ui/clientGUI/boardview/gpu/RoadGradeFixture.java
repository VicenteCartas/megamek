/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/** Editable matched grass/concrete specimens, authored through the same road and rail data as the editor. */
final class RoadGradeFixture {
    static final int CELL = 7, PANEL_WIDTH = CELL * 3, WIDTH = PANEL_WIDTH * 3, HEIGHT = CELL * 12;
    private static final int BASE = 3;
    private static final String[] DIRECTIONS = { "N", "NE", "SE", "S", "SW", "NW" };

    enum Panel {
        ROAD("Road only"), CENTERED("Road and centered rail"), BESIDE("Road and beside rail");
        final String label;
        Panel(String label) { this.label = label; }
    }

    record Case(String id, String label, int direction, int change) { }

    static List<Case> cases() {
        List<Case> result = new ArrayList<>();
        for (String shape : List.of("straight", "turn60", "turn120")) {
            for (int d = 0; d < 6; d++) {
                int change = (d % 2 == 0 ? 1 : -1) * (1 + d % 3);
                result.add(new Case(shape + "-" + d, shape + " from " + DIRECTIONS[d] + "; middle "
                      + (change > 0 ? "+" : "") + change + " levels"
                      + (shape.equals("turn120") ? "; rail uses gentle outside bypass" : ""), d, change));
            }
        }
        result.addAll(List.of(new Case("t-mixed", "T junction: three arms at different heights", 0, 1),
              new Case("x-mixed", "X junction: four arms and mixed ascending/descending landings", 0, 2),
              new Case("bridge-straight", "Straight deck: one lower bank and one higher bank", 0, 0),
              new Case("bridge-curved", "Descending curved bank onto a straight low deck", 0, -1),
              new Case("bridge-bent", "Bent deck with offset banks at different heights", 0, 1),
              new Case("bridge-overpass", "Elevated bridge over an independent ground road/rail course", 0, 0),
              new Case("cliff-curve", "Gentle descending curve beside an explicit three-level cliff", 0, -1),
              new Case("cut-valley", "Three-level road dip between six-level cliff tops", 0, -3),
              new Case("apron-flat", "Through road with an unmarked roadless concrete apron entrance", 0, 0),
              new Case("apron-slope", "Through road descending sideways to a roadless concrete apron", 0, -1),
              new Case("grade-straight", "Continuous straight incline: one level per hex", 0, 1),
              new Case("grade-curve", "Continuous gentle bend: one level per hex", 0, 1),
              new Case("grade-high-bank", "Continuous bend beside a higher inside plateau", 0, 1),
              new Case("grade-low-bank", "Continuous bend above lower inside ground", 0, 1),
              new Case("grade-transition", "Continuous incline with a centered/beside rail transition", 0, 1),
              new Case("bridge-graded", "Bent bridge with successively higher banks and decks", 0, 1),
              new Case("level-toe-straight", "Straight incline meeting two equal lower plateaus", 0, 1),
              new Case("level-toe-curve", "Curved incline meeting two equal lower plateaus", 0, 1)));
        return List.copyOf(result);
    }

    static Case named(String id) {
        return cases().stream().filter(sample -> sample.id().equals(id)).findFirst().orElseThrow();
    }

    static List<Case> ordinaryCases() {
        return cases().stream().filter(sample -> sample.id().startsWith("grade-") || sample.id().equals("bridge-graded"))
              .toList();
    }

    static Coords focusedCenter(int parity) { return new Coords(4 + parity, 4); }

    /** Geometry-only capture shared by the matrix and rotated regression specimens. */
    static BoardScene scene(Board board) {
        var tiles = new ArrayList<BoardScene.Tile>();
        var pixels = new BoardScene.PixelPool();
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                var at = new Coords(x, y);
                var artwork = new BoardArtwork.HexImage(at,
                      List.of(), Map.of(), null);
                tiles.add(BoardScene.captureTile(board.getHex(at), artwork, null, pixels, board::getHex));
            }
        }
        return new BoardScene(0, board.getWidth(), board.getHeight(), tiles,
              List.of(), List.of(), -1, "", List.of());
    }

    /** Small rotated specimens use precisely the same authoring as the saved visual matrix. */
    static Board focused(boolean concrete, Panel panel, String id, int direction, int parity) {
        var board = blank(concrete, 10, 9);
        var at = focusedCenter(parity);
        var sample = named(id);
        specimen(board, at, new Case(id, sample.label(), direction, sample.change()), panel != Panel.ROAD);
        if (panel == Panel.BESIDE) { beside(board, at); }
        if (id.equals("grade-transition")) { transition(board, at, direction, panel); }
        return board;
    }

    static Coords center(Panel panel, int index) {
        return new Coords(panel.ordinal() * PANEL_WIDTH + 3 + CELL * (index % 3), 3 + CELL * (index / 3));
    }

    static String filename(boolean concrete) {
        return "Road Grades - " + (concrete ? "Concrete" : "Grassland") + ".board2";
    }

    static Board create(boolean concrete) {
        Board board = blank(concrete, WIDTH, HEIGHT);
        board.setDocumentName("Road Grades - " + (concrete ? "Concrete" : "Grassland"));
        board.addTag("Example");
        board.setDescription("Matched road grading specimens. Three panels, left to right: road only, centered rail, "
              + "beside rail. Each panel has the same " + cases().size() + " annotated cases in three columns, "
              + "read left to right then down. "
              + "The first six rows rotate straight, gentle 60-degree, and sharp 120-degree roads through all six "
              + "directions. The middle rises or falls 1, 2, or 3 levels. Rails bypass a sharp road bend with two "
              + "legal gentle pieces. Further rows contain mixed-height T/X junctions, raised and lowered bridge "
              + "banks, a curved deck, an overpass, explicit cliffs, a deep cut and flat/sloped concrete aprons. "
              + "The final six ordinary cases add continuous inclines, inside corner cut/fill, a rail placement "
              + "transition and a graded bent bridge. Beside placement applies only to actual shared corridor sides. "
              + "The first 28 cases deliberately stress geometry; their steep heights do not imply that every "
              + "vehicle can traverse them. Inspect in Classic and Enhanced, "
              + "from above and both oblique directions. "
              + "Cell labels and exact serialized topology are in the CSV manifest.");
        var cases = cases();
        for (Panel panel : Panel.values()) {
            for (int index = 0; index < cases.size(); index++) {
                var sample = cases.get(index);
                var at = center(panel, index);
                specimen(board, at, sample, panel != Panel.ROAD);
                if (panel == Panel.BESIDE) { beside(board, at); }
                if (sample.id().equals("grade-transition")) { transition(board, at, sample.direction(), panel); }
                board.setAnnotations(at, List.of(panel.label + " / " + sample.id(), sample.label()));
            }
        }
        return board;
    }

    static Board blank(boolean concrete, int width, int height) {
        Board board = Board.createEmptyBoard(width, height);
        board.setNativeFormat(true);
        board.setRoadsAutoExit(false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Hex hex = board.getHex(x, y);
                hex.setLevel(BASE);
                if (concrete) { hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1)); }
            }
        }
        return board;
    }

    private static void specimen(Board board, Coords at, Case sample, boolean rail) {
        int d = sample.direction();
        if (sample.id().startsWith("level-toe-")) {
            int other = (d + (sample.id().endsWith("straight") ? 3 : 2)) % 6;
            var path = through(at, d, other, 2);
            for (int distance = 1; distance <= 2; distance++) {
                board.getHex(at.translated(d, distance)).setLevel(BASE + 1);
            }
            ways(board, path, Set.of(), rail);
            return;
        }
        if (sample.id().startsWith("grade-") || sample.id().equals("bridge-graded")) {
            boolean straight = sample.id().equals("grade-straight") || sample.id().equals("grade-transition");
            int other = (d + (straight ? 3 : 2)) % 6;
            var path = through(at, d, other, 2);
            var decks = sample.id().equals("bridge-graded") ? Set.of(at, at.translated(other)) : Set.<Coords>of();
            for (int i = 0; i < path.size(); i++) {
                var member = path.get(i);
                int level = BASE - 2 + i;
                if (decks.contains(member)) { bridge(board, member, level, true); }
                else { board.getHex(member).setLevel(level); }
            }
            ways(board, path, decks, rail);
            if (sample.id().equals("grade-high-bank") || sample.id().equals("grade-low-bank")) {
                board.getHex(at.translated((d + 1) % 6)).setLevel(BASE
                      + (sample.id().equals("grade-high-bank") ? 2 : -2));
            }
            return;
        }
        if (sample.id().startsWith("straight") || sample.id().startsWith("turn")) {
            int other = (d + (sample.id().startsWith("straight") ? 3 : sample.id().startsWith("turn60") ? 2 : 1)) % 6;
            var path = through(at, d, other, 2);
            board.getHex(at).setLevel(BASE + sample.change());
            ways(board, path, Set.of(), false);
            if (rail) {
                var track = new ArrayList<>(path);
                if (sample.id().startsWith("turn120")) { track.remove(at); }
                tracks(board, track, Set.of());
            }
            return;
        }
        switch (sample.id()) {
            case "t-mixed", "x-mixed" -> {
                int[] arms = sample.id().equals("t-mixed") ? new int[] { 0, 1, 3 } : new int[] { 0, 1, 3, 4 };
                int[] levels = { 4, 2, 3, 5 };
                for (int i = 0; i < arms.length; i++) {
                    int direction = Math.floorMod(d + arms[i], 6);
                    var arm = List.of(at, at.translated(direction), at.translated(direction, 2));
                    for (Coords member : arm.subList(1, arm.size())) { board.getHex(member).setLevel(levels[i]); }
                    ways(board, arm, Set.of(), rail);
                }
            }
            case "bridge-straight", "bridge-overpass" -> {
                var decks = Set.of(at.translated(0), at, at.translated(3));
                int level = sample.id().equals("bridge-overpass") ? 5 : 3;
                for (Coords member : decks) { bridge(board, member, level, sample.id().equals("bridge-straight")); }
                var path = through(at, 0, 3, 3);
                for (int side : new int[] { 0, 3 }) {
                    for (int distance : new int[] { 2, 3 }) {
                        board.getHex(at.translated(side, distance)).setLevel(level + (side == 0 ? -1 : 1));
                    }
                }
                ways(board, path, decks, rail);
                if (sample.id().equals("bridge-overpass")) {
                    var under = through(at, 1, 4, 2);
                    for (Coords member : under) { board.getHex(member).setLevel(2); }
                    // The centre's base rises with its ground route; the deck stays at its authored absolute height.
                    board.getHex(at).addTerrain(new Terrain(Terrains.BRIDGE_ELEV, level - 2));
                    ways(board, under, Set.of(), rail);
                }
            }
            case "bridge-curved", "bridge-bent" -> {
                boolean bent = sample.id().equals("bridge-bent");
                var path = through(at, 0, 2, 3);
                var decks = bent ? Set.of(at, at.translated(2)) : Set.of(at.translated(2), at.translated(2, 2));
                int level = bent ? 3 : 2;
                for (Coords member : decks) { bridge(board, member, level, true); }
                for (Coords member : path) {
                    if (!decks.contains(member)) {
                        board.getHex(member).setLevel(member.equals(path.getLast()) ? 2 : 3);
                    }
                }
                if (bent) {
                    board.getHex(at.translated(0)).setLevel(4);
                    board.getHex(at.translated(2, 2)).setLevel(2);
                }
                ways(board, path, decks, rail);
            }
            case "cliff-curve", "cut-valley" -> {
                boolean cut = sample.id().equals("cut-valley");
                ways(board, through(at, 0, cut ? 3 : 2, 2), Set.of(), rail);
                board.getHex(at).setLevel(cut ? 0 : 3);
                if (!cut) { board.getHex(at.translated(2)).setLevel(2); }
                for (int side : cut ? new int[] { 1, 2, 4, 5 } : new int[] { 5 }) {
                    Hex top = board.getHex(at.translated(side));
                    top.setLevel(6);
                    top.addTerrain(new Terrain(Terrains.CLIFF_TOP, 1, true, 1 << ((side + 3) % 6)));
                }
            }
            case "apron-flat", "apron-slope" -> {
                ways(board, through(at, 0, 3, 2), Set.of(), rail);
                road(board, at, 1 << 1, false);
                for (Coords member : List.of(at.translated(1), at.translated(1, 2), at.translated(1).translated(2))) {
                    board.getHex(member).setLevel(BASE + sample.change());
                    board.getHex(member).addTerrain(new Terrain(Terrains.PAVEMENT, 1));
                }
            }
            default -> throw new IllegalArgumentException(sample.id());
        }
    }

    static List<Coords> through(Coords at, int first, int second, int length) {
        List<Coords> result = new ArrayList<>();
        for (int i = length; i > 0; i--) { result.add(at.translated(first, i)); }
        result.add(at);
        for (int i = 1; i <= length; i++) { result.add(at.translated(second, i)); }
        return result;
    }

    static void bridge(Board board, Coords at, int deckLevel, boolean water) {
        Hex hex = board.getHex(at);
        hex.setLevel(0);
        if (water) {
            hex.removeTerrain(Terrains.PAVEMENT);
            hex.addTerrain(new Terrain(Terrains.WATER, 1));
        }
        hex.addTerrain(new Terrain(Terrains.BRIDGE, 1, true, 0));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_CF, 80));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, deckLevel));
        hex.setAppearance(Map.of("bridge", HexAppearance.BUILT_BRIDGE));
    }

    static void road(Board board, Coords at, int sides, boolean deck) {
        Hex hex = board.getHex(at);
        int type = deck ? Terrains.BRIDGE_ROAD : Terrains.ROAD;
        int previous = hex.containsTerrain(type) ? hex.getTerrain(type).getExits() : 0;
        hex.addTerrain(new Terrain(type, 1, true, previous | sides));
        if (deck) { hex.addTerrain(new Terrain(Terrains.BRIDGE, 1, true, previous | sides)); }
    }

    static void ways(Board board, List<Coords> path, Set<Coords> decks, boolean rail) {
        for (int i = 1; i < path.size(); i++) {
            Coords a = path.get(i - 1), b = path.get(i);
            int d = direction(a, b);
            road(board, a, 1 << d, decks.contains(a));
            road(board, b, 1 << ((d + 3) % 6), decks.contains(b));
        }
        if (rail) { tracks(board, path, decks); }
    }

    static void tracks(Board board, List<Coords> path, Set<Coords> decks) {
        for (int i = 0; i < path.size(); i++) {
            Coords at = path.get(i);
            int sides = (i == 0 ? 0 : 1 << direction(at, path.get(i - 1)))
                  | (i == path.size() - 1 ? 0 : 1 << direction(at, path.get(i + 1)));
            Hex hex = board.getHex(at);
            int type = decks.contains(at) ? Terrains.BRIDGE_RAIL : Terrains.RAIL;
            if (hex.containsTerrain(type)) { sides |= hex.getTerrain(type).getExits(); }
            RailRoute.lay(hex, decks.contains(at), sides, 0);
        }
    }

    static int direction(Coords a, Coords b) {
        for (int d = 0; d < 6; d++) { if (a.translated(d).equals(b)) { return d; } }
        throw new IllegalArgumentException("Nonadjacent fixture route: " + a + " to " + b);
    }

    private static void beside(Board board, Coords center) {
        Set<RailRoute.Member> done = new HashSet<>();
        for (int y = center.getY() - 3; y <= center.getY() + 3; y++) {
            for (int x = center.getX() - 3; x <= center.getX() + 3; x++) {
                Coords at = new Coords(x, y);
                for (var marker : RailRoute.markers(board.getHex(at), RailRoute.Kind.RAIL)) {
                    var start = new RailRoute.Member(at, marker);
                    if (done.contains(start)) { continue; }
                    var run = RailRoute.corridorRun(board::getHex, at, marker);
                    done.addAll(run);
                    RailRoute.besideBits(board::getHex, run, start).forEach((member, bits) ->
                          RailRoute.beside(board.getHex(member.at()), RailRoute.onDeck(member.marker()), bits));
                }
            }
        }
    }

    private static void transition(Board board, Coords at, int direction, Panel panel) {
        if (panel == Panel.ROAD) { return; }
        int reverse = (direction + 3) % 6;
        boolean incomingBeside = panel == Panel.BESIDE;
        for (int step = -2; step <= 2; step++) {
            var member = step < 0 ? at.translated(direction, -step) : at.translated(reverse, step);
            var hex = board.getHex(member);
            int sides = hex.getTerrain(Terrains.RAIL).getExits();
            int bits = step == 0 ? 1 << (incomingBeside ? direction : reverse)
                  : (step < 0 == incomingBeside ? sides : 0);
            RailRoute.beside(hex, false, bits);
        }
    }

    /** Export one deterministic manifest and both native boards, with an optional runtime mirror directory. */
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        StringBuilder manifest = new StringBuilder("board,panel,case,focus_hex,cell_bounds,label,route_hexes\n");
        for (boolean concrete : new boolean[] { false, true }) {
            Board board = create(concrete);
            Path file = output.resolve(filename(concrete));
            BoardFile.save(board, file);
            Board loaded = BoardFile.read(file);
            validate(loaded);
            for (Panel panel : Panel.values()) {
                for (int i = 0; i < cases().size(); i++) {
                    var sample = cases().get(i);
                    Coords at = center(panel, i);
                    List<String> routes = new ArrayList<>();
                    for (int y = at.getY() - 3; y <= at.getY() + 3; y++) {
                        for (int x = at.getX() - 3; x <= at.getX() + 3; x++) {
                            Coords member = new Coords(x, y);
                            Hex hex = loaded.getHex(member);
                            List<String> layers = new ArrayList<>();
                            if (hex.containsTerrain(Terrains.BRIDGE)) {
                                layers.add("deck=" + (hex.getLevel() + hex.terrainLevel(Terrains.BRIDGE_ELEV)));
                            }
                            for (int type : new int[] { Terrains.ROAD, Terrains.RAIL,
                                  Terrains.BRIDGE_ROAD, Terrains.BRIDGE_RAIL }) {
                                if (hex.containsTerrain(type)) {
                                    layers.add(Terrains.getName(type) + "=" + hex.getTerrain(type).getExits());
                                }
                            }
                            if (!layers.isEmpty()) {
                                routes.add(member.getBoardNum() + "@" + hex.getLevel() + ":" + String.join(";", layers));
                            }
                        }
                    }
                    manifest.append(csv(file.getFileName().toString())).append(',').append(panel).append(',')
                          .append(sample.id()).append(',').append(at.getBoardNum()).append(',')
                          .append(csv((at.getX() - 2) + ":" + (at.getY() - 2) + ".."
                                + (at.getX() + 4) + ":" + (at.getY() + 4)))
                          .append(',').append(csv(sample.label())).append(',')
                          .append(csv(String.join("|", routes))).append('\n');
                }
            }
            System.out.println(file + " : " + WIDTH + "x" + HEIGHT + ", "
                  + cases().size() * Panel.values().length + " cases");
            if (args.length > 1) {
                Files.createDirectories(Path.of(args[1]));
                Files.copy(file, Path.of(args[1]).resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path csv = output.resolve("road-grades-cases.csv");
        Files.writeString(csv, manifest);
        if (args.length > 1) {
            Files.copy(csv, Path.of(args[1]).resolve(csv.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String csv(String text) { return '"' + text.replace("\"", "\"\"") + '"'; }

    static void validate(Board board) {
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Coords at = new Coords(x, y);
                Hex hex = board.getHex(at);
                for (var marker : RailRoute.markers(hex, RailRoute.Kind.RAIL)) {
                    int sides = RailRoute.drawnSides(marker, hex);
                    if (Integer.bitCount(sides) > 1 && RailRoute.stranded(sides) != 0) {
                        throw new IllegalStateException("Fixture has an unsupported rail hairpin at " + at);
                    }
                    for (int d = 0; d < 6; d++) {
                        if ((sides & 1 << d) != 0
                              && RailRoute.joined(hex, marker, d, board.getHex(at.translated(d))) == null) {
                            throw new IllegalStateException("Unjoined fixture rail at " + at + " direction " + d);
                        }
                    }
                }
            }
        }
    }
}
