/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/**
 * Content shared by the editor and artwork resolver. Loading this index never loads a model or texture. It also holds
 * the Assets palette: categories of sections of cards, in data order. An entry (preset, variant or asset) is on the
 * palette when it names a section; entries sharing a {@code family} are one card.
 */
public final class BoardEditorBlueprint {
    public record Choice(int value, String label, String material) { }
    /** Height fields store levels above the owning hex and can be dragged in the editor's side section. */
    public record Field(String terrain, String label, boolean edges, boolean optional, List<Choice> choices,
          double min, double max, double step, boolean brush, boolean height) { }
    public record Component(String id, String label, List<String> present, String defaults, List<Field> fields,
          String category, String receiver, String preview, int previewLevel, boolean palette, boolean replaceExisting, List<Preset> presets) {
        public boolean isPresent(java.util.function.Predicate<String> hasTerrain) {
            return present.isEmpty() || present.stream().anyMatch(hasTerrain);
        }
    }
    /**
     * A complete paintable terrain choice; optional fields no longer require painting an empty component first.
     * {@code settings} are optional fields the Brush offers although the preset leaves them out; {@code hexes} make it a
     * multi-hex blueprint: by direction (0-5 = N, NE, SE, S, SW, NW) the terrain of that neighbour of the painted hex.
     */
    public record Preset(String id, String label, String terrain, List<String> sections, List<String> settings,
          Map<Integer, String> hexes) { }
    /**
     * {@code pieces}: a model per structure weight ({@link #model}) for a look that names no single asset;
     * {@code skin}: the scenery a vegetation look draws in place of its trees, or null.
     */
    public record Variant(String id, String label, String owner, String selectors, String asset, String thumbnail,
          String material, boolean blend, List<String> sections, String family, String pieces, Skin skin) {
        /** The model this design shows on {@code hex}: its asset, else its pieces for the structure's weight ("" if none). */
        public String model(Hex hex) {
            Terrain structure = hex.getAnyTerrainOf(Terrains.BUILDING, Terrains.FUEL_TANK);
            if (!asset.isBlank() || pieces.isBlank() || structure == null || structure.getLevel() < 1 || structure.getLevel() > WEIGHTS.size()) {
                return asset;
            }
            return pieces.replace("{weight}", WEIGHTS.get(structure.getLevel() - 1));
        }
    }
    /**
     * A vegetation look that draws its own scenery inside its cover instead of trees (a container yard): per cover level
     * of the hex ({@link #cover(Hex)}) one {@code layouts.json} composition ({@code layouts}), turned by the design's
     * rotation ({@link HexAppearance#rotation}). {@code legacy}: by rotation, in data order, the legacy art tokens the
     * look stands for (the conversion's {@link #reproducing} and the 2D board's {@link #artwork(Hex, String)});
     * {@code sprites}: the skin whose legacy tokens the 2D board draws for a look without any. {@code mirror}: the skin
     * that is this one's mirror image across its east-west axis (the quay crane's other hand), which a board flipped
     * along one axis shows ({@link #flipped}); empty for a skin that stands for its own mirror image (rows). {@code cover}:
     * the cover level the look's card shows and its brush paints ({@link #terrain()}).
     */
    public record Skin(String cover, Map<String, String> layouts, Map<Integer, List<Terrain>> legacy, String sprites,
          String mirror) {
        /** The cover levels, lowest first; each skin names a layout for all four. */
        public static final List<String> COVERS = List.of("foliage", "light", "heavy", "ultra");
        private static final List<String> TERRAIN = List.of("woods:1;foliage_elev:1", "woods:1;foliage_elev:2",
              "woods:2;foliage_elev:2", "woods:3;foliage_elev:3");

        /**
         * The hex's cover level: none without woods or jungle (cleared or burnt down), foliage for a canopy one level
         * high, else light, heavy or ultra by the denser of woods and jungle.
         */
        public static String cover(Hex hex) {
            int density = Math.max(hex.terrainLevel(Terrains.WOODS), hex.terrainLevel(Terrains.JUNGLE));
            if (density < 1) { return null; }
            return hex.terrainLevel(Terrains.FOLIAGE_ELEV) == 1 ? COVERS.getFirst() : COVERS.get(Math.min(density, 3));
        }

        /** The woods of this look's {@code cover}, as its card shows and its brush paints them. */
        public String terrain() { return TERRAIN.get(COVERS.indexOf(cover)); }

        /** The rotation whose legacy tokens hold {@code token}, or null. */
        private Integer rotation(Terrain token) {
            for (var group : legacy.entrySet()) {
                if (group.getValue().stream().anyMatch(art -> selects(art, token))) { return group.getKey(); }
            }
            return null;
        }

        /**
         * The legacy tokens the 2D board draws for this look turned {@code rotation} degrees: those of the nearest legacy
         * rotation, a half turn counting as the same (rows read alike turned round), then the exact one (a crane's
         * boom), then data order.
         */
        private List<Terrain> sprites(int rotation) {
            Comparator<Integer> halfTurns = Comparator.comparingInt(turn -> apart(turn, rotation, 180));
            return legacy.get(legacy.keySet().stream().min(halfTurns.thenComparingInt(turn -> apart(turn, rotation, 360)))
                  .orElseThrow());
        }

        /** How many degrees apart two rotations lie, counting turns of {@code period} as none. */
        private static int apart(int a, int b, int period) {
            int difference = Math.floorMod(a - b, period);
            return Math.min(difference, period - difference);
        }
    }
    /** Local X/Y in board units, Z in board levels. Heading is the connector's outward XY direction. */
    public record Connector(double x, double y, double z, double heading) { }
    public record Snap(String set, List<Connector> connectors, double radius, double angleTolerance) { }
    /**
     * A palette object. {@code layout}, when not null, makes it a stamp: painting places that scenery layout's
     * objects as one new group, and no object of this id is ever created. Otherwise painting places the model of its id.
     * A decal's card shows its image by path convention ({@link BoardDecalArt#image}); {@code thumbnail} is a stamp's.
     */
    public record Asset(String id, String label, String kind, String thumbnail, Snap snap, String layout,
          List<String> sections, String family) { }
    /** One palette entry; {@code key} is the session's brush key: "owner/presetId", a variant id or an asset id. */
    public record Entry(String key, String owner, String label, Preset preset, Variant variant, Asset asset) {
        public List<String> sections() {
            return preset != null ? preset.sections() : variant != null ? variant.sections() : asset.sections();
        }

        /** The family label, or null. */
        public String family() { return variant != null ? variant.family() : asset != null ? asset.family() : null; }

        /** A composition: a stamp layout or a multi-hex preset (the BLUEPRINT chip). */
        public boolean blueprint() { return asset != null && asset.layout() != null || preset != null && !preset.hexes().isEmpty(); }
    }
    /** A card: one entry, a picker family (assets) or a gallery (variants), labelled with the family. */
    public record Card(String label, List<Entry> members) {
        public boolean family() { return members.size() > 1; }
        public boolean gallery() { return family() && members.getFirst().variant() != null; }
        /** A picker family: assets on one card, which a Type row swaps. */
        public boolean picker() { return family() && !gallery(); }
    }
    /** {@code subcategory}: the subcategory that shows it, behind one card of the category's page; null for that page itself. */
    public record Section(String category, String id, String subcategory, List<Card> cards) {
        public String path() { return category + "/" + id; }
    }
    /**
     * {@code themes}: null except for the Theme category (its special themes); {@code sections}: all of them, the
     * subcategories' too; {@code iconNode}: one hex in the .board2 form; {@code shelves}: the page section whose shelf shows
     * a subcategory's card, for those not on the top shelf.
     */
    public record Category(String id, List<String> themes, List<Section> sections, String iconNode, Map<String, String> shelves) {
        /** The subcategories, in order. */
        public List<String> subcategories() {
            return sections.stream().map(Section::subcategory).filter(Objects::nonNull).distinct().toList();
        }

        /** The subcategories whose cards {@code section}'s shelf shows; null: the top shelf's. */
        public List<String> subcategories(String section) {
            return subcategories().stream().filter(subcategory -> Objects.equals(shelves.get(subcategory), section)).toList();
        }

        /** The sections of {@code subcategory}; null: those on the category's own page. */
        public List<Section> sections(String subcategory) {
            return sections.stream().filter(section -> Objects.equals(section.subcategory(), subcategory)).toList();
        }

        /** A fresh hex each call. */
        public Hex icon() {
            try { return BoardFile.fromClipboard(HEX + iconNode); }
            catch (IOException failure) { throw new IllegalStateException("Unreadable icon of " + id, failure); }
        }
    }

    private static final String HEX = "MegaMek Hex2\n";
    private static final List<String> DIRECTIONS = List.of("N", "NE", "SE", "S", "SW", "NW");
    /** Building levels 1-4 name their pieces' weight. */
    private static final List<String> WEIGHTS = List.of("light", "medium", "heavy", "hard");

    private final List<Component> components;
    private final Map<String, Variant> variants;
    /**
     * The variants with one selector, and the skins, by the terrain type of their selector or legacy tokens
     * ({@link #tokens}): the designs a legacy token can stand for. An asset design (a building model) is not among them:
     * its selector is the art its model's tileset image draws beside that model, which only the model decides
     * ({@link BoardSceneryLayouts}'s conversion); the 2D board of a {@code .board2} still draws it through the selector
     * ({@link #artwork(Hex)}).
     */
    private final Map<Integer, List<Variant>> selecting = new HashMap<>();
    private final Map<String, Asset> assets;
    private final List<Category> categories;
    private final Map<String, Entry> entries;
    private final Map<String, Card> cards;

    private static BoardEditorBlueprint shared;

    public static synchronized BoardEditorBlueprint get() {
        if (shared == null) { shared = loadDefault(); }
        return shared;
    }

    private static BoardEditorBlueprint loadDefault() {
        try {
            return read(Configuration.dataDir().toPath().resolve("board-editor/blueprint.json"));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load the board editor blueprint", failure);
        }
    }

    private BoardEditorBlueprint(List<Component> components, Map<String, Variant> variants, Map<String, Asset> assets,
          List<Category> categories, Map<String, Entry> entries, Map<String, Card> cards) {
        this.components = List.copyOf(components);
        this.variants = Collections.unmodifiableMap(variants);
        this.assets = Collections.unmodifiableMap(assets);
        this.categories = List.copyOf(categories);
        this.entries = Collections.unmodifiableMap(entries);
        this.cards = Map.copyOf(cards);
        for (Variant variant : variants.values()) {
            for (int type : tokens(variant).stream().map(Terrain::getType).distinct().toList()) {
                selecting.computeIfAbsent(type, key -> new ArrayList<>()).add(variant);
            }
        }
    }

    public static BoardEditorBlueprint read(Path file) throws IOException {
        JsonNode root = new ObjectMapper().readTree(file.toFile());
        if (!"megamek-editor-blueprint".equals(root.path("format").asText()) || root.path("version").asInt() != 1) {
            throw new IOException("Unsupported board editor blueprint: " + file);
        }
        // The palette's sections by path, in declared order; each collects its cards below.
        Map<String, List<Card>> sections = new LinkedHashMap<>();
        for (JsonNode category : root.withArray("categories")) {
            String id = required(category, "id");
            for (JsonNode section : category.withArray("sections")) {
                if (sections.putIfAbsent(id + "/" + section.asText(), new ArrayList<>()) != null) {
                    throw new IOException("Duplicate palette section " + id + "/" + section.asText());
                }
            }
            try { BoardFile.fromClipboard(HEX + category.path("icon")); }
            catch (IOException failure) { throw new IOException("Unreadable icon of " + id, failure); }
        }
        List<Component> components = new ArrayList<>();
        Map<String, Variant> variants = new LinkedHashMap<>();
        Map<String, Asset> assets = new LinkedHashMap<>();
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (JsonNode entry : root.withArray("components")) {
            List<Field> fields = new ArrayList<>();
            for (JsonNode field : entry.withArray("fields")) {
                List<Choice> choices = new ArrayList<>();
                for (JsonNode choice : field.withArray("choices")) {
                    choices.add(new Choice(choice.path("value").asInt(), required(choice, "label"), choice.path("material").asText()));
                }
                String terrain = required(field, "terrain");
                if (Terrains.getType(terrain) <= 0) { throw new IOException("Unknown terrain " + terrain); }
                double min = field.path("min").asDouble(0), max = field.path("max").asDouble(10), step = field.path("step").asDouble(1);
                if (!Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(step) || min >= max || step <= 0) {
                    throw new IOException("Invalid slider range for " + terrain);
                }
                fields.add(new Field(terrain, required(field, "label"), field.path("edges").asBoolean(),
                      field.path("optional").asBoolean(), List.copyOf(choices), min, max, step, field.path("brush").asBoolean(),
                      field.path("height").asBoolean()));
            }
            List<String> present = strings(entry.path("present"));
            for (String terrain : present) {
                if (Terrains.getType(terrain) <= 0) { throw new IOException("Unknown component terrain " + terrain); }
            }
            String id = required(entry, "id");
            if (components.stream().anyMatch(c -> c.id().equals(id))) { throw new IOException("Duplicate component"); }
            List<Preset> presets = new ArrayList<>();
            for (JsonNode node : entry.withArray("presets")) {
                Map<Integer, String> hexes = new TreeMap<>();
                for (var neighbour : node.path("hexes").properties()) {
                    int direction = DIRECTIONS.indexOf(neighbour.getKey());
                    if (direction < 0) { throw new IOException("Unknown direction " + neighbour.getKey() + " of " + node.path("id")); }
                    hexes.put(direction, terrain(node, neighbour.getValue().asText()));
                }
                List<String> settings = strings(node.path("settings"));
                for (String setting : settings) {
                    if (fields.stream().noneMatch(field -> field.terrain().equals(setting))) {
                        throw new IOException("Setting " + setting + " of " + node.path("id") + " is no field of " + id);
                    }
                }
                Preset preset = new Preset(required(node, "id"), required(node, "label"), terrain(node, node.path("terrain").asText()),
                      sections(node, sections), settings, Collections.unmodifiableMap(hexes));
                presets.add(preset);
                add(entries, new Entry(id + "/" + preset.id(), id, preset.label(), preset, null, null));
            }
            components.add(new Component(id, required(entry, "label"), present,
                  entry.path("defaults").asText(), List.copyOf(fields), entry.path("category").asText("Terrain"),
                  entry.path("receiver").asText(id),
                  entry.path("preview").path("terrain").asText(entry.path("defaults").asText()),
                  entry.path("preview").path("level").asInt(0), entry.path("palette").asBoolean(true),
                  entry.path("replaceExisting").asBoolean(true), List.copyOf(presets)));
        }
        for (JsonNode entry : root.withArray("variants")) {
            Variant variant = new Variant(required(entry, "id"), required(entry, "label"), required(entry, "owner"),
                  entry.path("selectors").asText(), entry.path("asset").asText(), entry.path("thumbnail").asText(),
                  entry.path("material").asText(), entry.path("blend").asBoolean(), sections(entry, sections),
                  entry.path("family").asText(null), entry.path("pieces").asText(), skin(entry));
            if (!HexAppearance.OWNERS.contains(variant.owner()) || variants.putIfAbsent(variant.id(), variant) != null
                  || variant.skin() != null && (!variant.owner().equals("vegetation") || !variant.selectors().isBlank()
                        || !variant.asset().isBlank())) {
                throw new IOException("Invalid or duplicate variant " + variant.id());
            }
            add(entries, new Entry(variant.id(), variant.owner(), variant.label(), null, variant, null));
        }
        checkSkins(variants);
        for (JsonNode entry : root.withArray("assets")) {
            Asset asset;
            // Reuse the document's key and transform validation; the catalog cannot escape the asset root.
            try {
                asset = new Asset(required(entry, "id"), required(entry, "label"), required(entry, "kind"),
                      entry.path("thumbnail").asText(), snap(entry),
                      entry.hasNonNull("layout") ? entry.get("layout").asText() : null,
                      sections(entry, sections), entry.path("family").asText(null));
                new BoardDecoration("check", asset.kind(), asset.id(), null, 0, 0, 0, false, 1,
                      BoardDecoration.Placement.ground(), 0);
            } catch (IllegalArgumentException failure) { throw new IOException("Invalid asset " + entry.path("id"), failure); }
            if (assets.putIfAbsent(asset.id(), asset) != null) { throw new IOException("Duplicate asset " + asset.id()); }
            add(entries, new Entry(asset.id(), "", asset.label(), null, null, asset));
        }
        // Cards in data order (presets, variants, assets); a family is one card where its first member stands.
        Map<String, List<Entry>> families = new LinkedHashMap<>();
        for (Entry entry : entries.values()) {
            if (entry.family() != null) { families.computeIfAbsent(entry.family(), family -> new ArrayList<>()).add(entry); }
        }
        for (var family : families.entrySet()) {
            Entry first = family.getValue().getFirst();
            if (family.getValue().size() < 2 || family.getValue().stream().anyMatch(member -> (member.variant() == null) != (first.variant() == null)
                  || !member.owner().equals(first.owner()) || !member.sections().equals(first.sections()) || member.blueprint() != first.blueprint()
                  || member.asset() != null && !member.asset().kind().equals(first.asset().kind()))) {
                throw new IOException("Family " + family.getKey() + " needs two or more members of one kind, owner and sections");
            }
        }
        Map<String, Card> cards = new HashMap<>();
        for (Entry entry : entries.values()) {
            if (cards.containsKey(entry.key())) { continue; }
            List<Entry> members = entry.family() == null ? List.of(entry) : List.copyOf(families.get(entry.family()));
            Card card = new Card(entry.family() == null ? entry.label() : entry.family(), members);
            members.forEach(member -> cards.put(member.key(), card));
            entry.sections().forEach(path -> sections.get(path).add(card));
        }
        List<Category> categories = new ArrayList<>();
        for (JsonNode category : root.withArray("categories")) {
            String id = category.path("id").asText();
            Map<String, String> subcategories = new HashMap<>();
            for (var subcategory : category.path("subcategories").properties()) {
                for (String section : strings(subcategory.getValue())) {
                    if (!sections.containsKey(id + "/" + section) || subcategories.putIfAbsent(section, subcategory.getKey()) != null) {
                        throw new IOException("Subcategory " + subcategory.getKey() + " has an unknown or taken section " + section);
                    }
                }
            }
            List<Section> list = new ArrayList<>();
            for (JsonNode section : category.withArray("sections")) {
                list.add(new Section(id, section.asText(), subcategories.get(section.asText()), List.copyOf(sections.get(id + "/" + section.asText()))));
            }
            Map<String, String> shelves = new HashMap<>();
            for (var shelf : category.path("subcategoryShelf").properties()) {
                String section = shelf.getValue().asText();
                if (!subcategories.containsValue(shelf.getKey()) || !sections.containsKey(id + "/" + section) || subcategories.containsKey(section)) {
                    throw new IOException("Subcategory " + shelf.getKey() + " needs a section of its category's page for its card, not " + section);
                }
                shelves.put(shelf.getKey(), section);
            }
            categories.add(new Category(id, category.has("themes") ? strings(category.get("themes")) : null, List.copyOf(list),
                  category.path("icon").toString(), Map.copyOf(shelves)));
        }
        return new BoardEditorBlueprint(components, variants, assets, categories, entries, cards);
    }

    private static String required(JsonNode node, String name) throws IOException {
        String value = node.path(name).asText();
        if (value.isBlank()) { throw new IOException("Blueprint requires " + name); }
        return value;
    }

    /** A string or a list of strings; none when absent or empty. */
    private static List<String> strings(JsonNode value) {
        List<String> result = new ArrayList<>();
        if (value.isArray()) { value.forEach(item -> result.add(item.asText())); }
        else if (!value.asText().isEmpty()) { result.add(value.asText()); }
        return List.copyOf(result);
    }

    /** The entry's palette sections ({@code section}: one path or a list); each must be declared. */
    private static List<String> sections(JsonNode entry, Map<String, ?> declared) throws IOException {
        List<String> paths = strings(entry.path("section"));
        for (String path : paths) {
            if (!declared.containsKey(path)) { throw new IOException("Unknown palette section " + path + " of " + entry.path("id")); }
        }
        return paths;
    }

    private static String terrain(JsonNode preset, String terrain) throws IOException {
        List<String> errors = new ArrayList<>();
        if (!new Hex(0, terrain, "").isValid(errors)) { throw new IOException("Invalid brush " + preset.path("id") + ": " + errors); }
        return terrain;
    }

    /** Only palette entries (with a section) are entries; their keys are unique. */
    private static void add(Map<String, Entry> entries, Entry entry) throws IOException {
        if (!entry.sections().isEmpty() && entries.putIfAbsent(entry.key(), entry) != null) {
            throw new IOException("Duplicate palette entry " + entry.key());
        }
    }

    /** A variant's {@code skin}, or null: a layout for all four covers, rotations in 30-degree steps, tokens that parse. */
    private static Skin skin(JsonNode variant) throws IOException {
        if (!variant.has("skin")) { return null; }
        JsonNode skin = variant.get("skin");
        String id = variant.path("id").asText();
        Map<String, String> layouts = new LinkedHashMap<>();
        for (String cover : Skin.COVERS) { layouts.put(cover, required(skin.path("layouts"), cover)); }
        Map<Integer, List<Terrain>> legacy = new LinkedHashMap<>();
        for (var group : skin.path("legacy").properties()) {
            List<Terrain> tokens = new ArrayList<>();
            int rotation;
            try {
                rotation = Integer.parseInt(group.getKey());
                for (String token : strings(group.getValue())) { tokens.add(new Terrain(token)); }
            } catch (RuntimeException failure) { throw new IOException("Invalid legacy tokens of " + id, failure); }
            if (rotation < 0 || rotation >= 360 || rotation % 30 != 0 || tokens.isEmpty()) {
                throw new IOException("Invalid skin rotation " + rotation + " of " + id);
            }
            legacy.put(rotation, List.copyOf(tokens));
        }
        String cover = skin.path("cover").asText("light");
        if (!Skin.COVERS.contains(cover)) { throw new IOException("Unknown cover " + cover + " of " + id); }
        return new Skin(cover, Collections.unmodifiableMap(layouts), Collections.unmodifiableMap(legacy),
              skin.path("sprites").asText(), skin.path("mirror").asText());
    }

    /** The legacy tokens a variant stands for: its one selector, or its skin's legacy tokens; none for an asset design. */
    private static List<Terrain> tokens(Variant variant) {
        if (variant.skin() != null) { return variant.skin().legacy().values().stream().flatMap(List::stream).toList(); }
        return variant.selectors().isBlank() || variant.selectors().contains(";") || !variant.asset().isBlank()
              ? List.of() : List.of(new Terrain(variant.selectors()));
    }

    /**
     * Each skin's legacy tokens stand for that one design: no other token of any variant selects one or is selected by
     * one; a skin's {@code sprites} names a skin with legacy tokens, its {@code mirror} a skin whose mirror it is.
     */
    private static void checkSkins(Map<String, Variant> variants) throws IOException {
        for (Variant variant : variants.values()) {
            if (variant.skin() == null) { continue; }
            Variant sprites = variants.get(variant.skin().sprites());
            if (variant.skin().legacy().isEmpty() && (sprites == null || sprites.skin() == null || sprites.skin().legacy().isEmpty())) {
                throw new IOException("Skin " + variant.id() + " has no legacy tokens and draws no other skin's sprites");
            }
            Variant mirror = variants.get(variant.skin().mirror());
            if (!variant.skin().mirror().isEmpty() && (mirror == null || mirror.skin() == null || !mirror.skin().mirror().equals(variant.id()))) {
                throw new IOException("Skin " + variant.id() + " names a mirror that does not name it back");
            }
            List<Terrain> own = tokens(variant);
            for (Variant other : variants.values()) {
                List<Terrain> theirs = tokens(other);
                for (int i = 0; i < own.size(); i++) {
                    for (int j = 0; j < theirs.size(); j++) {
                        if ((other != variant || i != j) && (selects(own.get(i), theirs.get(j)) || selects(theirs.get(j), own.get(i)))) {
                            throw new IOException("Legacy token " + own.get(i) + " of " + variant.id() + " also stands for " + other.id());
                        }
                    }
                }
            }
        }
    }

    /** Whether a design's {@code selector} stands for {@code token}: its type and level, and its exits where it names them. */
    private static boolean selects(Terrain selector, Terrain token) {
        return selector.getType() == token.getType() && selector.getLevel() == token.getLevel()
              && (!selector.hasExitsSpecified() || token.hasExitsSpecified() && selector.getExits() == token.getExits());
    }

    private static Snap snap(JsonNode asset) throws IOException {
        if (!asset.has("snap")) { return null; }
        JsonNode definition = asset.path("snap");
        // The radius in board units: how far apart two ends may lie and still join.
        double radius = definition.path("radius").asDouble(8), angle = definition.path("angleTolerance").asDouble(2);
        if (!Double.isFinite(radius) || radius <= 0 || radius > HexTile.HEIGHT / 2.0 || !Double.isFinite(angle) || angle < 0
              || angle > 15) {
            throw new IOException("Invalid snapping tolerance for " + asset.path("id"));
        }
        List<Connector> connectors = new ArrayList<>();
        for (JsonNode point : definition.withArray("connectors")) {
            if (!point.isArray() || point.size() != 4) { throw new IOException("A connector needs [x, y, z, heading]"); }
            for (JsonNode number : point) {
                if (!number.isNumber() || !Double.isFinite(number.asDouble())) { throw new IOException("A connector needs finite coordinates"); }
            }
            connectors.add(new Connector(point.get(0).asDouble(), point.get(1).asDouble(), point.get(2).asDouble(), point.get(3).asDouble()));
        }
        if (connectors.isEmpty()) { throw new IOException("Snapping needs at least one connector"); }
        return new Snap(required(definition, "set"), List.copyOf(connectors), radius, angle);
    }

    public List<Component> components() { return components; }
    public List<Variant> variants(String owner) { return variants.values().stream().filter(v -> v.owner().equals(owner)).toList(); }
    public List<Variant> variants() { return List.copyOf(variants.values()); }
    public Variant variant(String id) { return variants.get(id); }
    public List<Asset> assets() { return List.copyOf(assets.values()); }
    public Asset asset(String id) { return assets.get(id); }
    public Component component(String id) {
        return components.stream().filter(c -> c.id().equals(id)).findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Unknown component " + id));
    }

    /** The palette's categories in start-page order. */
    public List<Category> categories() { return categories; }

    /** The palette entry of a brush key; null for a key that is no palette entry ("hex/sample", an automatic piece). */
    public Entry entry(String key) { return entries.get(key); }

    /** The card that shows {@code entry} (the same card in each of its sections). */
    public Card card(Entry entry) { return cards.get(entry.key()); }

    /** The Theme category (the one with {@code themes}), or null. */
    public Category themeCategory() { return categories.stream().filter(c -> c.themes() != null).findFirst().orElse(null); }

    /** The Theme category's themes: the special ones the tilesets do not list. */
    public List<String> specialThemes() { return themeCategory() == null ? List.of() : themeCategory().themes(); }

    /** A theme's name: its tileset id written as words, "Grass" for no theme. */
    public static String themeLabel(String theme) {
        String words = (theme.isEmpty() ? "grass" : theme).replace('_', ' ').replace('-', ' ');
        return words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
    }

    /** The category of a section path ("category/section"). */
    public static String categoryOf(String section) { return section.substring(0, section.indexOf('/')); }

    /**
     * The design that stands for a legacy art token: the variant whose one selector is {@code token}'s type and level
     * (and its exits, where the selector names them), or the skin, at its rotation, whose legacy tokens hold it; owned
     * by a component present on {@code hex}; a blend variant only where the token's exits are a strength of one to five
     * sixths ({@link #artwork(Hex, String)} adds them back). Null when no variant reproduces it.
     */
    public HexAppearance reproducing(Hex hex, Terrain token) {
        for (Variant variant : selecting.getOrDefault(token.getType(), List.of())) {
            int exits = token.hasExitsSpecified() ? token.getExits() : 0;
            Integer rotation = variant.skin() == null ? null : variant.skin().rotation(token);
            if ((variant.skin() == null ? !selects(new Terrain(variant.selectors()), token) : rotation == null)
                  || variant.blend() && (!token.hasExitsSpecified() || exits < 1 || exits > 5)
                  || !component(variant.owner()).isPresent(name -> hex.containsTerrain(Terrains.getType(name)))) {
                continue;
            }
            return new HexAppearance(variant.id(), null, null, variant.blend() ? exits / 6.0 : null,
                  BoardDecoration.Colours.NONE, rotation);
        }
        return null;
    }

    /**
     * Whether the hex's FLUFF token is woods art that a vegetation design stands for ({@link #reproducing}: an orchard,
     * a container yard), which that design draws in the 3D views instead of the token's tileset image.
     */
    public boolean vegetationArt(Hex hex) {
        Terrain fluff = hex.getTerrain(Terrains.FLUFF);
        HexAppearance design = fluff == null ? null : reproducing(hex, fluff);
        return design != null && variants.get(design.variant()).owner().equals("vegetation");
    }

    /** {@code source} with every owner's design selectors ({@link #artwork(Hex, String)}): what its tokens would draw. */
    public Hex artwork(Hex source) {
        Hex result = source;
        for (String owner : source.getAppearance().keySet()) { result = artwork(result, owner); }
        return result;
    }

    /**
     * The vegetation design {@code design} on a board that {@code BoardUtilities.flip} flips ({@code horizontal}: east
     * for west, {@code vertical}: north for south): a skin turns with the board, a half turn when both flip; one axis
     * mirrors it, which its {@code mirror} skin turned shows (a skin without one stands for its own mirror image, as
     * rows read alike mirrored). Any other design stays as it is.
     */
    public HexAppearance flipped(HexAppearance design, boolean horizontal, boolean vertical) {
        Variant variant = variants.get(design.variant());
        if (variant == null || variant.skin() == null || !horizontal && !vertical) { return design; }
        int rotation = design.rotation() == null ? 0 : design.rotation();
        boolean mirrored = horizontal != vertical;
        String look = mirrored && !variant.skin().mirror().isEmpty() ? variant.skin().mirror() : variant.id();
        // Mirrored east for west, a look turned r is its east-west mirror image turned 180 - r; north for south, -r.
        int turned = !mirrored ? rotation + 180 : horizontal ? 180 - rotation : -rotation;
        return new HexAppearance(look, null, null, null, BoardDecoration.Colours.NONE, Math.floorMod(turned, 360));
    }

    /**
     * Temporary, owner-scoped artwork query. Gameplay terrain and other owners' selectors are never rewritten. A skin
     * adds the first legacy token of its rotation ({@link Skin#sprites}; its {@code sprites} skin's for a look without
     * any), which the tileset draws where the hex's woods match that sprite's; a hex already holding one of those tokens
     * (a game of a loaded {@code .board}: its own colours) keeps its own.
     */
    public Hex artwork(Hex source, String owner) {
        HexAppearance appearance = source.getAppearance().get(owner);
        Variant variant = appearance == null ? null : variants.get(appearance.variant());
        if (variant != null && variant.owner().equals(owner) && variant.skin() != null) {
            Skin skin = variant.skin().legacy().isEmpty() ? variants.get(variant.skin().sprites()).skin() : variant.skin();
            List<Terrain> sprites = skin.sprites(appearance.rotation() == null ? 0 : appearance.rotation());
            Terrain own = source.getTerrain(Terrains.FLUFF);
            if (own != null && sprites.stream().anyMatch(art -> selects(art, own))) { return source; }
            Hex result = source.duplicate();
            result.addTerrain(new Terrain(sprites.getFirst()));
            return result;
        }
        if (variant == null || !variant.owner().equals(owner) || variant.selectors().isBlank()) { return source; }
        Hex result = source.duplicate();
        for (String selector : variant.selectors().split(";")) { result.addTerrain(new Terrain(selector)); }
        if (variant.blend()) {
            Terrain transition = result.getTerrain(Terrains.GROUND_FLUFF);
            if (transition != null) {
                result.addTerrain(new Terrain(Terrains.GROUND_FLUFF, transition.getLevel(), true,
                      appearance.strength() == null ? 3 : (int) Math.round(appearance.strength() * 6)));
            }
        }
        return result;
    }
}
