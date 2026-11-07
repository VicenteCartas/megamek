/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import megamek.common.Hex;
import megamek.common.units.Terrains;
import megamek.common.util.BoardUtilities;

/** Shared board-file boundary. Native documents contain the Board's rules and authored visuals, never a projection. */
public final class BoardFile {
    public static final String EXTENSION = ".board2";
    public static final int VERSION = 3;
    private static BoardFlag flag(JsonNode object) {
        if (!object.has("flag")) { return BoardFlag.DEFAULT; }
        JsonNode flag = object.get("flag");
        requireObject(flag);
        return new BoardFlag(string(flag, "pattern", "solid"), string(flag, "emblem", ""),
              number(flag, "x", 0), number(flag, "y", 0), number(flag, "scale", 1), number(flag, "rotation", 0));
    }

    private static final ObjectMapper JSON = new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private BoardFile() { }

    public static boolean isBoardName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".board") || lower.endsWith(EXTENSION);
    }

    public static boolean isNativeName(String name) { return name.toLowerCase(Locale.ROOT).endsWith(EXTENSION); }

    public static String withoutExtension(String name) {
        return isBoardName(name) ? name.substring(0, name.length() - (isNativeName(name) ? 7 : 6)) : name;
    }

    /** Names sent by the lobby retain the native extension; historical extensionless selections remain valid. */
    public static String fileName(String name) { return isBoardName(name) ? name : name + ".board"; }
    public static String selectionName(String file) { return isNativeName(file) ? file : withoutExtension(file); }

    public record Metadata(BoardDimensions size, Set<String> tags) { }

    /** Index native headers without constructing hexes, models, or a game. Also accepts reordered JSON fields. */
    public static Metadata metadata(Path file) throws IOException {
        ObjectNode header = JSON.createObjectNode();
        try (JsonParser parser = JSON.getFactory().createParser(file.toFile())) {
            if (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.START_OBJECT) { throw new IOException("Expected board object"); }
            while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_OBJECT) {
                if (parser.currentToken() != com.fasterxml.jackson.core.JsonToken.FIELD_NAME) { throw new IOException("Malformed board header"); }
                String name = parser.currentName(); parser.nextToken();
                if (Set.of("format", "size", "tags").contains(name)) {
                    header.set(name, JSON.reader().without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(parser));
                } else { parser.skipChildren(); }
            }
            if (parser.nextToken() != null) { throw new IOException("Trailing board data"); }
        }
        try {
            require("megamek-board".equals(string(header, "format", "")), "Not a MegaMek board document");
            JsonNode size = header.get("size"); requireObject(size);
            int width = integer(size, "width", 0), height = integer(size, "height", 0);
            require(width > 0 && height > 0, "Invalid board size");
            Set<String> tags = new HashSet<>();
            if (header.has("tags")) {
                require(header.get("tags").isArray(), "Invalid tags");
                for (JsonNode tag : header.get("tags")) { require(tag.isTextual(), "Invalid tag"); tags.add(tag.textValue()); }
            }
            return new Metadata(new BoardDimensions(width, height), Set.copyOf(tags));
        } catch (IllegalArgumentException failure) { throw new IOException(failure.getMessage(), failure); }
    }

    /** Keep old names resolving to old files when both formats exist; explicit native names are never rewritten. */
    public static Path resolve(Path directory, String name) {
        if (isBoardName(name)) { return directory.resolve(name); }
        Path legacy = directory.resolve(name + ".board");
        return Files.exists(legacy) ? legacy : directory.resolve(name + EXTENSION);
    }

    public static boolean looksNative(String text) {
        String start = text.stripLeading();
        if (start.startsWith("\uFEFF")) { start = start.substring(1).stripLeading(); }
        return start.startsWith("{") || start.startsWith("[");
    }

    /** Reads a board file of either format; a board that breaks the game's rules is refused ({@link #read(Path, boolean)}). */
    public static Board read(Path path) throws IOException { return read(path, false); }

    /**
     * Reads a board file of either format. With {@code continueOnError} a board that breaks the game's rules
     * ({@link Board#isValid}) is read anyway, as the old editor's {@code board.load(is, null, true)} read one: only the
     * board editor does this, to list the errors and fix them. Games, the lobby, map previews and random-map selection
     * read strictly and refuse such a board. A malformed file is refused either way.
     */
    public static Board read(Path path, boolean continueOnError) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (isNativeName(path.toString()) || looksNative(text)) { return readNative(text, continueOnError); }
        Board board = new Board();
        List<String> errors = new ArrayList<>();
        try (InputStream stream = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))) {
            board.load(stream, errors, continueOnError);
        } catch (RuntimeException failure) {
            throw new IOException("Cannot read board: " + failure.getMessage(), failure);
        }
        if (board.getWidth() <= 0 || board.getHeight() <= 0 || !continueOnError && !errors.isEmpty()) {
            throw new IOException("Invalid board: " + String.join("; ", errors));
        }
        return board;
    }

    /**
     * Reads terrain for a flat thumbnail using the same format parser, tolerating rules errors as lobby pictures do.
     * Native appearance and decoration fields are omitted, so no model IDs, assets or scenery are resolved. The result
     * is a private preview board, not a document to save or a board to play.
     */
    public static Board readTerrain(InputStream stream) throws IOException {
        byte[] content = stream.readAllBytes();
        String text = new String(content, StandardCharsets.UTF_8);
        if (looksNative(text)) { return readNative(text, true, false); }
        Board board = new Board();
        board.load(new ByteArrayInputStream(content), null, true);
        return board;
    }

    /**
     * The one load of a map sheet wherever a sheet becomes (part of) a board to play or to look at: the game start
     * ({@code TWBoardTransformer}), the lobby's preview of that board ({@code ServerBoardHelper}), scenarios and the file
     * preview. It loads the file, converts its legacy scenery ({@link BoardSceneryLayouts#convert}) and flips it both
     * ways when {@code rotate}. Conversion comes before the flip, so a turned sheet keeps the art (and the car parks'
     * lanes) its file draws. Only a probe that reads the size and edges ({@code convert} false: the lobby's
     * deployment-zone probes) skips the conversion. The sheet keeps its file's board type: a game's sheets get the
     * medium's when {@code BoardUtilities.combine} joins them.
     *
     * @throws IllegalArgumentException when the file cannot be read as a board
     */
    public static Board loadSheet(File file, boolean rotate, boolean convert) {
        Board sheet = new Board();
        sheet.load(file);
        if (convert) { BoardSceneryLayouts.convert(sheet); }
        BoardUtilities.flip(sheet, rotate, rotate);
        return sheet;
    }

    /** Reads a .board2 document; one that breaks the game's rules is refused ({@link #readNative(String, boolean)}). */
    public static Board readNative(String text) throws IOException { return readNative(text, false); }

    /**
     * Reads a .board2 document. {@code continueOnError} reads a board that breaks the game's rules ({@link Board#isValid})
     * anyway, as {@link #read(Path, boolean)} does. Coordinates, terrain values and object IDs are checked either way;
     * fields the reader does not use are ignored.
     */
    public static Board readNative(String text, boolean continueOnError) throws IOException {
        return readNative(text, continueOnError, true);
    }

    private static Board readNative(String text, boolean continueOnError, boolean visuals) throws IOException {
        try {
            JsonNode root = JSON.readTree(text.startsWith("\uFEFF") ? text.substring(1) : text);
            requireObject(root);
            require("megamek-board".equals(string(root, "format", null)), "Not a MegaMek board document");
            JsonNode size = root.get("size");
            requireObject(size);
            int width = integer(size, "width", 0), height = integer(size, "height", 0);
            JsonNode entries = root.get("hexes");
            require(entries != null && entries.isArray(), "hexes must be an array");
            long count = (long) width * height;
            require(width > 0 && height > 0 && count <= Integer.MAX_VALUE && count == entries.size(),
                  "Board dimensions must match the complete hex array");
            Hex[] hexes = new Hex[(int) count];
            Set<String> ids = new HashSet<>();
            for (JsonNode entry : entries) {
                requireObject(entry);
                Coords coords = coordinate(entry.get("at"));
                require(coords.getX() >= 0 && coords.getY() >= 0 && coords.getX() < width && coords.getY() < height,
                      "Hex outside board: " + coords);
                int index = coords.getY() * width + coords.getX();
                require(hexes[index] == null, "Duplicate hex coordinates: " + coords);
                require(entry.has("elevation"), "Hex elevation is required");
                Hex hex = new Hex(integer(entry, "elevation", 0), "", string(entry, "theme", null), coords);
                var appearances = BoardComponents.read(entry.get("components"), hex, visuals);
                if (visuals) {
                    ObjectNode visual = JSON.createObjectNode().set("appearance", appearances);
                    if (entry.has("decorations")) { visual.set("decorations", entry.get("decorations")); }
                    if (entry.has("structure")) { visual.set("structure", entry.get("structure")); }
                    readVisuals(visual, hex);
                }
                for (BoardDecoration decoration : hex.getDecorations()) {
                    require(ids.add(decoration.id()), "Duplicate board object ID: " + decoration.id());
                }
                hexes[index] = hex;
            }
            Board board = new Board();
            if (root.has("options")) {
                JsonNode options = root.get("options");
                requireObject(options);
                board.setRoadsAutoExit(bool(options, "exitRoadsToPavement", true));
            }
            board.setBoardType(BoardType.valueOf(string(root, "boardType", "GROUND")));
            board.setDocumentName(string(root, "name", null));
            board.setDescription(string(root, "description", null));
            board.setSourceHeader(string(root, "sourceHeader", null));
            if (root.has("tags")) {
                require(root.get("tags").isArray(), "tags must be an array");
                for (JsonNode tag : root.get("tags")) {
                    require(tag.isTextual(), "A tag must be a string");
                    require(!board.getTags().contains(tag.textValue()), "Duplicate tag");
                    board.addTag(tag.textValue());
                }
            }
            if (root.has("notes")) {
                require(root.get("notes").isArray(), "notes must be an array");
                for (JsonNode note : root.get("notes")) {
                    requireObject(note);
                    Coords at = coordinate(note.get("at"));
                    require(!board.getAnnotations().containsKey(at), "Duplicate note coordinate");
                    require(note.has("text") && note.get("text").isArray(), "Note text must be an array");
                    List<String> lines = new ArrayList<>();
                    for (JsonNode line : note.get("text")) {
                        require(line.isTextual(), "Note text must contain strings");
                        lines.add(line.textValue());
                    }
                    board.setAnnotations(at, List.copyOf(lines));
                }
            }
            List<String> errors = new ArrayList<>();
            board.newData(width, height, hexes, errors);
            if (!continueOnError) {
                board.isValid(errors);
                require(errors.isEmpty(), "Invalid terrain: " + String.join("; ", errors));
            }
            board.setNativeFormat(true);
            return board;
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IOException("Invalid .board2: " + failure.getMessage(), failure);
        }
    }

    private static void validateTerrain(String terrain) {
        Set<Integer> seen = new HashSet<>();
        if (terrain.isEmpty()) { return; }
        for (String token : terrain.split(";", -1)) {
            String[] parts = token.split(":", -1);
            require(parts.length == 2 || parts.length == 3, "Malformed terrain: " + token);
            int type = Terrains.getType(parts[0]);
            require(type != 0 || parts[0].equals(Terrains.getName(0)), "Unknown terrain: " + parts[0]);
            require(seen.add(type), "Duplicate terrain: " + parts[0]);
            for (int i = 1; i < parts.length; i++) { Integer.parseInt(parts[i]); }
        }
    }

    /** A placed object's or a truss bridge's colours: an array of #rrggbb values or null per slot; none when absent. */
    private static BoardDecoration.Colours colours(JsonNode owner) {
        if (!owner.has("colours")) { return BoardDecoration.Colours.NONE; }
        JsonNode slots = owner.get("colours");
        require(slots.isArray(), "colours must be an array of #rrggbb values or null");
        List<String> replacements = new ArrayList<>();
        for (JsonNode slot : slots) {
            require(slot.isNull() || slot.isTextual(), "colours must be an array of #rrggbb values or null");
            replacements.add(slot.isNull() ? null : slot.asText());
        }
        return new BoardDecoration.Colours(replacements);
    }

    private static void putColours(ObjectNode owner, BoardDecoration.Colours colours) {
        if (colours.slots().isEmpty()) { return; }
        ArrayNode slots = owner.putArray("colours");
        colours.slots().forEach(slot -> { if (slot == null) { slots.addNull(); } else { slots.add(slot); } });
    }

    private static void readVisuals(JsonNode entry, Hex hex) {
        if (entry.has("structure")) {
            var structure = entry.get("structure");
            var anchor = structure.path("anchor");
            hex.setBuildingReference(new BuildingVolumes.Reference(structure.path("id").asText(),
                  new Coords(anchor.path(0).asInt() - 1, anchor.path(1).asInt() - 1)));
        }
        if (entry.has("appearance")) {
            JsonNode appearances = entry.get("appearance");
            requireObject(appearances);
            Map<String, HexAppearance> values = new LinkedHashMap<>();
            appearances.fields().forEachRemaining(field -> {
                if (!HexAppearance.OWNERS.contains(field.getKey())) { return; }
                JsonNode style = field.getValue();
                requireObject(style);
                var control = style.has("trafficControl") ? HexAppearance.TrafficControl.valueOf(
                      string(style, "trafficControl", "").toUpperCase(java.util.Locale.ROOT)) : null;
                if (control != null) {
                    require(field.getKey().equals("road") || field.getKey().equals("bridgeRoad"), "Traffic controls belong to a road");
                }
                String variant = string(style, "variant", null), asset = asset(style), material = string(style, "material", null);
                var colours = colours(style);
                Integer rotation = style.has("rotation") ? integer(style, "rotation", 0) : null;
                double padding = style.path("padding").asDouble();
                // Two lanes are the default and store none; a single lane's direction needs the single lane.
                Integer lanes = style.has("lanes") ? integer(style, "lanes", 0) : null;
                if (lanes != null && lanes == 2) { lanes = null; }
                Integer inbound = lanes != null && style.has("inbound") ? sides(style.get("inbound")) : null;
                // A two-lane road's exits list the sides that may take each of them; a single lane has none.
                Long exitFrom = lanes == null && style.has("exitFrom") ? exitApproaches(style.get("exitFrom")) : null;
                if (exitFrom != null && exitFrom == 0) { exitFrom = null; }
                // Explicit default values (rotation 0, no colours) are the generic look, which stores no appearance.
                if (variant == null && asset == null && material == null && control == null && lanes == null
                      && exitFrom == null && (rotation == null || rotation == 0) && padding <= 0 && colours.slots().isEmpty()) {
                    return;
                }
                HexAppearance value = new HexAppearance(variant, asset,
                      material, style.has("strength") ? number(style, "strength", 0) : null,
                      colours, rotation,
                      style.has("tracks") ? integer(style, "tracks", 0) : null,
                      style.has("beside") ? integer(style, "beside", 0) : null, control, retainedUid(style),
                      padding <= 0 ? null : padding, lanes, inbound, exitFrom);
                value.validateOwner(field.getKey());
                values.put(field.getKey(), value);
            });
            hex.setAppearance(values);
        }
        if (entry.has("decorations")) {
            require(entry.get("decorations").isArray(), "decorations must be an array");
            List<BoardDecoration> values = new ArrayList<>();
            for (JsonNode object : entry.get("decorations")) {
                requireObject(object);
                double x = 0, y = 0;
                if (object.has("position")) {
                    JsonNode position = object.get("position");
                    require(position.isArray() && position.size() == 2, "position must contain X and Y");
                    x = finite(position.get(0)); y = finite(position.get(1));
                }
                var stretch = BoardDecoration.Stretch.NONE;
                if (object.has("stretch")) {
                    JsonNode factors = object.get("stretch");
                    require(factors.isArray() && factors.size() == 3, "stretch must contain X, Y and Z");
                    stretch = new BoardDecoration.Stretch(finite(factors.get(0)), finite(factors.get(1)), finite(factors.get(2)));
                }
                var colours = colours(object);
                String kind = string(object, "kind", null);
                require(!"prop".equals(kind) || !object.has("drawOrder"), "Props cannot have paint order");
                JsonNode placement = object.get("placement");
                requireObject(placement);
                BoardDecoration.Receiver receiver = null;
                if (placement.has("receiver")) {
                    JsonNode target = placement.get("receiver");
                    requireObject(target);
                    receiver = new BoardDecoration.Receiver(string(target, "terrain", null), string(target, "surface", null));
                }
                values.add(new BoardDecoration(string(object, "id", null), kind, asset(object),
                      string(object, "name", null), x, y, number(object, "rotation", 0), bool(object, "mirror", false),
                      number(object, "scale", 1), new BoardDecoration.Placement(string(placement, "mode", null),
                            placement.has("level") ? number(placement, "level", 0) : null, receiver,
                            placement.has("offset") ? number(placement, "offset", 0) : null), integer(object, "drawOrder", 0),
                      bool(object, "clipToHex", "decal".equals(kind)), number(object, "rotationX", 0), number(object, "rotationY", 0),
                      string(object, "group", null), bool(object, "bare", false), integer(object, "connections", 0), stretch,
                      colours, integer(object, "tracks", 0), bool(object, "pillars", false), retainedUid(object), integer(object, "levels", 0), flag(object)));
            }
            hex.setDecorations(values);
        }
    }

    /**
     * Compact, deterministic output: even a decorated hex occupies a single physical line. The legacy scenery tokens
     * the conversion took are not written ({@link BoardSceneryLayouts#withoutTakenTokens}); {@code board} is not changed.
     * A board that breaks the game's rules is written too, as the old editor saved one: the readers decide, and only the
     * board editor reads it ({@link #read(Path, boolean)}).
     *
     * @throws IllegalStateException when {@code board} was never converted ({@link BoardSceneryLayouts#convert}) but holds
     *       such a token
     */
    public static void write(Board board, OutputStream stream) throws IOException {
        if (board.getWidth() <= 0 || board.getHeight() <= 0) { throw new IOException("Cannot save an empty board"); }
        Set<String> ids = new HashSet<>();
        ObjectNode root = JSON.createObjectNode().put("format", "megamek-board").put("version", VERSION);
        root.putObject("size").put("width", board.getWidth()).put("height", board.getHeight());
        put(root, "name", board.getDocumentName());
        put(root, "description", board.getDescription());
        put(root, "sourceHeader", board.getSourceHeader());
        if (board.getBoardType() != BoardType.GROUND) { root.put("boardType", board.getBoardType().name()); }
        if (!board.getRoadsAutoExit()) { root.putObject("options").put("exitRoadsToPavement", false); }
        if (!board.getTags().isEmpty()) {
            ArrayNode tags = root.putArray("tags"); board.getTags().stream().sorted().forEach(tags::add);
        }
        if (!board.getAnnotations().isEmpty()) {
            ArrayNode notes = root.putArray("notes");
            board.getAnnotations().entrySet().stream().sorted(Comparator
                  .comparingInt((Map.Entry<Coords, ?> e) -> e.getKey().getY()).thenComparingInt(e -> e.getKey().getX()))
                  .forEach(entry -> {
                      ObjectNode note = notes.addObject(); at(note, entry.getKey());
                      ArrayNode lines = note.putArray("text"); entry.getValue().forEach(lines::add);
                  });
        }
        // Validate identity before touching the stream. Atomic disk saves below also isolate all write failures.
        // The hexes as written: without the legacy tokens the conversion took (the board itself keeps them).
        Hex[] written = new Hex[board.getWidth() * board.getHeight()];
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Hex hex = board.getHex(x, y);
                if (hex == null) { throw new IOException("Cannot save a missing hex"); }
                for (BoardDecoration decoration : hex.getDecorations()) {
                    if (!ids.add(decoration.id())) { throw new IOException("Duplicate board object ID: " + decoration.id()); }
                }
                written[y * board.getWidth() + x] = BoardSceneryLayouts.withoutTakenTokens(hex);
                if (!board.isNativeFormat() && written[y * board.getWidth() + x] != hex) {
                    throw new IllegalStateException("Convert the board before writing it: hex "
                          + new Coords(x, y).getBoardNum() + " holds legacy scenery that was never converted");
                }
            }
        }
        String header = JSON.writeValueAsString(root);
        stream.write((header.substring(0, header.length() - 1) + ",\n\"hexes\":[\n").getBytes(StandardCharsets.UTF_8));
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                ObjectNode entry = hexNode(written[y * board.getWidth() + x], true); at(entry, new Coords(x, y));
                BoardComponents.write(written[y * board.getWidth() + x], entry);
                String comma = x == board.getWidth() - 1 && y == board.getHeight() - 1 ? "" : ",";
                stream.write((JSON.writeValueAsString(entry) + comma + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        stream.write("]}\n".getBytes(StandardCharsets.UTF_8));
        stream.flush();
    }

    public static void save(Board board, Path destination) throws IOException {
        if (!isNativeName(destination.toString())) { throw new IOException("New boards must be saved as .board2"); }
        Path absolute = destination.toAbsolutePath();
        Path temporary = Files.createTempFile(absolute.getParent(), ".board2-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) { write(board, output); }
            try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING); }
            board.setNativeFormat(true);
        } finally { Files.deleteIfExists(temporary); }
    }

    /** Authored state for editor comparisons; omit exits derived from neighbours so they do not count as direct edits. */
    public static ObjectNode hexNode(Hex hex) { return hexNode(hex, false); }

    private static ObjectNode hexNode(Hex hex, boolean saveConnections) {
        ObjectNode node = JSON.createObjectNode().put("elevation", hex.getLevel());
        if (hex.getBuildingReference() != null) {
            var reference = hex.getBuildingReference();
            var structure = node.putObject("structure").put("id", reference.id());
            structure.putArray("anchor").add(reference.anchor().getX() + 1).add(reference.anchor().getY() + 1);
        }
        String terrain = Arrays.stream(hex.getTerrainTypes()).filter(t -> !Terrains.AUTOMATIC.contains(t)).sorted()
              .mapToObj(t -> saveConnections ? hex.getTerrain(t).toBoardString() : hex.getTerrain(t).toString())
              .collect(Collectors.joining(";"));
        if (!terrain.isEmpty()) { node.put("terrain", terrain); }
        if (hex.getTheme() != null && !hex.getTheme().isEmpty()) { node.put("theme", hex.getTheme()); }
        if (!hex.getAppearance().isEmpty()) {
            ObjectNode styles = node.putObject("appearance");
            hex.getAppearance().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                ObjectNode style = styles.putObject(entry.getKey()); HexAppearance value = entry.getValue();
                put(style, "variant", value.variant()); putAsset(style, value.asset(), value.uid()); put(style, "material", value.material());
                if (value.strength() != null) { style.put("strength", value.strength()); }
                if (value.rotation() != null) { style.put("rotation", value.rotation()); }
                if (value.padding() != null) { style.put("padding", value.padding()); }
                if (value.tracks() != null) { style.put("tracks", value.tracks()); }
                if (value.beside() != null) { style.put("beside", value.beside()); }
                if (value.trafficControl() != null) { style.put("trafficControl", value.trafficControl().name().toLowerCase(java.util.Locale.ROOT)); }
                if (value.lanes() != null) { style.put("lanes", value.lanes()); }
                putSides(style, "inbound", value.inbound());
                if (value.exitFrom() != null) {
                    ObjectNode exits = style.putObject("exitFrom");
                    for (int exit = 0; exit < 6; exit++) { putSides(exits, SIDES.get(exit), (int) (value.exitFrom() >>> 6 * exit & 63)); }
                }
                putColours(style, value.colours());
            });
        }
        if (!hex.getDecorations().isEmpty()) {
            ArrayNode objects = node.putArray("decorations");
            hex.getDecorations().forEach(value -> {
                ObjectNode object = objects.addObject().put("id", value.id()).put("kind", value.kind());
                putAsset(object, value.asset(), value.uid());
                put(object, "name", value.name());
                put(object, "group", value.group());
                if (value.x() != 0 || value.y() != 0) { object.putArray("position").add(value.x()).add(value.y()); }
                if (value.rotation() != 0) { object.put("rotation", value.rotation()); }
                if (value.rotationX() != 0) { object.put("rotationX", value.rotationX()); }
                if (value.rotationY() != 0) { object.put("rotationY", value.rotationY()); }
                if (value.mirror()) { object.put("mirror", true); }
                if (value.bare()) { object.put("bare", true); }
                if (value.connections() != 0) { object.put("connections", value.connections()); }
                if (value.tracks() != 0) { object.put("tracks", value.tracks()); }
                if (value.pillars()) { object.put("pillars", true); }
                if (BoardFlag.ASSET.equals(value.asset())) {
                    var flag = value.flag();
                    var settings = object.putObject("flag");
                    settings.put("pattern", flag.pattern());
                    settings.put("emblem", flag.emblem());
                    settings.put("x", flag.x()); settings.put("y", flag.y());
                    settings.put("scale", flag.scale()); settings.put("rotation", flag.rotation());
                }
                if (value.levels() > 0) { object.put("levels", value.levels()); }
                if (value.scale() != 1) { object.put("scale", value.scale()); }
                if (!value.stretch().equals(BoardDecoration.Stretch.NONE)) {
                    object.putArray("stretch").add(value.stretch().x()).add(value.stretch().y()).add(value.stretch().z());
                }
                putColours(object, value.colours());
                if (value.kind().equals("decal")) { object.put("clipToHex", value.clipToHex()); }
                if (value.drawOrder() != 0) { object.put("drawOrder", value.drawOrder()); }
                ObjectNode placement = object.putObject("placement").put("mode", value.placement().mode());
                if (value.placement().level() != null) { placement.put("level", value.placement().level()); }
                if (value.placement().receiver() != null) {
                    placement.putObject("receiver").put("terrain", value.placement().receiver().terrain())
                          .put("surface", value.placement().receiver().surface());
                    placement.put("offset", value.placement().offset());
                }
            });
        }
        return node;
    }

    public static String clipboard(Hex hex) { return "MegaMek Hex2\n" + hexNode(hex, true); }

    public static Hex fromClipboard(String text) throws IOException {
        try {
            JsonNode node = JSON.readTree(text.substring("MegaMek Hex2\n".length()));
            requireObject(node);
            String terrain = string(node, "terrain", ""); validateTerrain(terrain);
            Hex hex = new Hex(integer(node, "elevation", 0), terrain, string(node, "theme", null));
            readVisuals(node, hex); hex.duplicateDecorationIds();
            return hex;
        } catch (RuntimeException failure) { throw new IOException("Invalid clipboard hex", failure); }
    }

    private static void at(ObjectNode node, Coords at) { node.putArray("at").add(at.getX() + 1).add(at.getY() + 1); }
    private static void put(ObjectNode node, String key, String value) { if (value != null) { node.put(key, value); } }

    /** A model's path and its stable id ({@link BoardModelIds}), so the board still finds it once it moves. */
    private static void putAsset(ObjectNode node, String asset, String uid) {
        put(node, "asset", asset);
        put(node, "uid", uid == null ? BoardModelIds.uid(asset) : uid);
    }

    /** Known IDs are derivable; retain an unknown saved ID so installing its pack later can still relocate the asset. */
    private static String retainedUid(JsonNode node) {
        String uid = string(node, "uid", null);
        return uid != null && !uid.equals(BoardModelIds.uid(asset(node))) ? uid : null;
    }

    /** A saved model's current path: by its stable id, else as saved. */
    private static String asset(JsonNode node) { return BoardModelIds.path(string(node, "uid", null), string(node, "asset", null)); }
    private static void require(boolean condition, String message) { if (!condition) { throw new IllegalArgumentException(message); } }
    private static void requireObject(JsonNode node) {
        require(node != null && node.isObject(), "Expected an object");
    }
    private static String string(JsonNode node, String key, String fallback) {
        if (!node.has(key)) { return fallback; }
        require(node.get(key).isTextual(), key + " must be a string"); return node.get(key).textValue();
    }
    private static int integer(JsonNode node, String key, int fallback) {
        if (!node.has(key)) { return fallback; }
        return intValue(node.get(key));
    }
    private static int intValue(JsonNode value) {
        require(value != null && value.isIntegralNumber() && value.canConvertToInt(), "Expected an integer"); return value.intValue();
    }
    private static double finite(JsonNode value) {
        require(value != null && value.isNumber() && Double.isFinite(value.doubleValue()), "Expected a finite number"); return value.doubleValue();
    }
    private static double number(JsonNode node, String key, double fallback) { return node.has(key) ? finite(node.get(key)) : fallback; }
    private static boolean bool(JsonNode node, String key, boolean fallback) {
        if (!node.has(key)) { return fallback; }
        require(node.get(key).isBoolean(), key + " must be boolean"); return node.get(key).booleanValue();
    }
    /** Hex sides by name, in direction order, as a single lane's direction stores them. */
    private static final List<String> SIDES = List.of("n", "ne", "se", "s", "sw", "nw");

    /** A side mask, when there is one, as a list of side names. */
    private static void putSides(ObjectNode node, String key, Integer sides) {
        if (sides == null || sides == 0) { return; }
        ArrayNode names = node.putArray(key);
        for (int side = 0; side < 6; side++) {
            if ((sides & 1 << side) != 0) { names.add(SIDES.get(side)); }
        }
    }

    /** Exit approaches by exit side name, each a list of side names, as bits {@code 6 * exit + side}. */
    private static long exitApproaches(JsonNode value) {
        require(value != null && value.isObject(), "Expected exit approaches by exit side");
        long result = 0;
        for (var exit : (Iterable<Map.Entry<String, JsonNode>>) value::fields) {
            require(SIDES.contains(exit.getKey()), "Unknown side: " + exit.getKey());
            result |= (long) sides(exit.getValue()) << 6 * SIDES.indexOf(exit.getKey());
        }
        return result;
    }

    /** A list of side names as a side mask. */
    private static int sides(JsonNode value) {
        require(value != null && value.isArray(), "Expected a list of sides");
        int result = 0;
        for (JsonNode side : value) {
            require(side.isTextual() && SIDES.contains(side.textValue()), "Unknown side: " + side);
            result |= 1 << SIDES.indexOf(side.textValue());
        }
        return result;
    }
    private static Coords coordinate(JsonNode value) {
        require(value != null && value.isArray() && value.size() == 2, "Expected [column,row]");
        int x = intValue(value.get(0)), y = intValue(value.get(1));
        require(x != Integer.MIN_VALUE && y != Integer.MIN_VALUE, "Coordinate overflow");
        return new Coords(x - 1, y - 1);
    }
}
