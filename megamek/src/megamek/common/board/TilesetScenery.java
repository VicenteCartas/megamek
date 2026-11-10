/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

import de.javagl.jgltf.model.io.GltfAssetReader;
import de.javagl.jgltf.model.io.v2.GltfAssetV2;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.enums.BuildingType;
import megamek.common.units.IBuilding;
import megamek.common.units.Terrains;
import megamek.logging.MMLogger;

/**
 * The 3D board's tileset art of a hex, as text: which entries of the Classic graphics' tileset
 * ({@code data/models/board/classic/tileset/classic.tileset}, used whatever the player's 2D tileset, parsed once per
 * JVM) a hex selects, how the 3D board treats each scenery layer, and which layers the legacy conversion takes. An
 * entry names a Classic image, or the key of art the 3D board never draws as an image: a structure's model
 * ({@code buildings/...}), the scenery layout ({@code scenery/...}) or legacy decal ({@code decal/...}) the conversion
 * turns a token into, the quay sprite ({@code quay/...}), a road ({@code route}: the 3D roads draw it), a bridge
 * ({@code bridge}: both graphics draw the 3D bridge), a container yard ({@code design}: its design draws it), water
 * and rapids ({@code water}: the water animation, tinted or toned; frozen, its still first frame under the ice), space
 * ({@code space}: transparent) and molten magma ({@code magma}: its animated surface covers the column). It reads no
 * image, so the conversion runs headless on the server; the 3D board's paint ({@code BoardArtwork}) paints from the
 * same result.
 * Thread-safe: the parsed tileset is shared and only read.
 */
public final class TilesetScenery {
    private static final MMLogger LOGGER = MMLogger.create(TilesetScenery.class);
    /** The parsed tileset of the data directory it was read from ({@link TilesetMatcher#root}). */
    private static volatile TilesetMatcher tileset;
    /** The structure models the tileset names that do not exist, each reported once. */
    private static final Set<String> MISSING = ConcurrentHashMap.newKeySet();
    /** File decisions belong to one data root and asset-reload generation, shared by capture and model readers. */
    private record BuildingFile(File custom, boolean customExists, boolean legacyExists) { }
    private record BuildingFiles(File root, Map<String, BuildingFile> files) { }
    private static volatile BuildingFiles buildingFiles;
    private static final Map<File, Boolean> SINGLE_HEX_BUILDINGS = new ConcurrentHashMap<>();

    /** The terrains whose tileset art is scenery: a layer matching one of them is classified ({@link #resolve}). */
    public static final List<Integer> SCENERY_TERRAINS = List.of(Terrains.FLUFF, Terrains.GROUND_FLUFF,
          Terrains.ROAD_FLUFF, Terrains.WATER_FLUFF, Terrains.FORTIFIED, Terrains.GEYSER, Terrains.SOLARIS_ELEVATOR,
          Terrains.INDUSTRIAL_ELEVATOR, Terrains.RUBBLE);

    private TilesetScenery() { }

    /**
     * A hex's 3D scenery: each model keeps the terrains its artwork matched, which tell the conversion which legacy token
     * it decodes. {@code decals} are the painted layers that are legacy decal ids ({@link BoardDecalArt#legacy}), which
     * the conversion turns into decal objects.
     */
    public record Scenery(List<Model> sources, Set<Integer> terrains, int cosmeticRoadExits, List<Model> decals) {
        /** A 3D scenery key and the hex terrains its tileset image matched. */
        public record Model(String key, Set<Integer> terrains) {
            public Model { terrains = Set.copyOf(terrains); }
        }
        public static final Scenery EMPTY = new Scenery(List.of(), Set.of(), 0);
        public Scenery {
            sources = List.copyOf(sources);
            terrains = Set.copyOf(terrains);
            decals = List.copyOf(decals);
        }
        public Scenery(List<Model> sources, Set<Integer> terrains, int cosmeticRoadExits) {
            this(sources, terrains, cosmeticRoadExits, List.of());
        }
        public List<String> models() { return sources.stream().map(Model::key).toList(); }
    }

    /** How the 3D board treats a scenery layer. */
    public enum Kind {
        /** Drawn by its {@code layouts.json} rows: {@link Layer#key} is the model key. */
        MODEL,
        /** A road-fluff surface variant, which the road's own material draws. */
        ROAD_SURFACE,
        /** Painted on the surface; a legacy decal id when {@link Layer#key} is set. */
        PAINT,
        /**
         * Neither drawn nor converted: a structure's model, a road-fluff kerb, rough or a vegetation design's woods art
         * (orchard, container yard) covers it.
         */
        SKIPPED,
        /** The old perspective quay sprite, which the fitted quay wall replaces. */
        QUAY
    }

    /**
     * One scenery layer of a hex: its picked entry file, the hex terrains it matched, its kind, its key (a MODEL's
     * layout key {@code scenery/...}, or a PAINT layer's legacy decal id {@code decal/...}, else null), and whether the
     * legacy conversion takes it. Taken are exactly the model layers and legacy decals whose token is decodable
     * ({@link BoardSceneryLayouts#imported}), pillar art on a bridge included; the 3D board must not draw those again.
     */
    public record Layer(TilesetMatcher.Pick pick, Set<Integer> terrains, Kind kind, String key, boolean taken) {
        public Layer { terrains = Set.copyOf(terrains); }

        /** The picked file with '/' separators. */
        public String file() { return pick.source(); }

        /** The tileset's transparent wildcard image, which a token value without art selects: it draws nothing. */
        public boolean blank() { return file().equals(BLANK); }
    }

    /** The transparent image of the tileset's wildcard entries ({@code fluff:*}, {@code road_fluff:*} and the like). */
    private static final String BLANK = "tileset/blank.png";

    /** A hex's scenery layers in drawing order, and the {@link Scenery} they give. */
    public record Resolved(List<Layer> layers, Scenery scenery) {
        public Resolved { layers = List.copyOf(layers); }
    }

    /**
     * The 3D board's tileset, parsed on first use and again when the data directory changed since
     * ({@link Configuration#setDataDir}), so its definitions and the images read beside them ({@link TilesetMatcher#root})
     * always come from the current one.
     */
    public static TilesetMatcher tileset() {
        TilesetMatcher result = tileset;
        if (result == null || !result.root().equals(root())) {
            synchronized (TilesetScenery.class) {
                if (tileset == null || !tileset.root().equals(root())) { tileset = load(); }
                result = tileset;
            }
        }
        return result;
    }

    /** Rereads the 3D board's tileset after an artist edits it (the 3D views' asset reload); returns the new one. */
    public static synchronized TilesetMatcher reload() {
        tileset = load();
        buildingFiles = null;
        SINGLE_HEX_BUILDINGS.clear();
        BuildingVolumes.reload();
        VARIANTS.clear();
        MISSING.clear();
        return tileset;
    }

    private static File root() { return new File(Configuration.dataDir(), "models/board/classic"); }

    private static TilesetMatcher load() {
        var result = new TilesetMatcher(root());
        try {
            result.load("tileset/classic.tileset");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load the Classic tileset", exception);
        }
        return result;
    }

    /**
     * New kits use family/name paths; the selected legacy asset keeps its tileset provenance for fallback. A kit named
     * for the design alone stands for every exit value of its per-combination keys ({@code ..._NN}): its connection
     * pieces follow the hex's exits.
     */
    public static File customBuildingFile(String asset) {
        return buildingFile(asset).custom();
    }

    /** A core-only authored GLB uses the stacking contract but has no pieces for connected hexsides. */
    public static boolean singleHexBuilding(String asset) {
        if (asset == null || !asset.startsWith("buildings/")) { return false; }
        var file = buildingFile(asset);
        if (!file.customExists() || !file.custom().getName().endsWith(".glb")) { return false; }
        return SINGLE_HEX_BUILDINGS.computeIfAbsent(file.custom().getAbsoluteFile(), path -> {
            try (var input = Files.newInputStream(path.toPath())) {
                var data = new GltfAssetReader().readWithoutReferences(input);
                if (!(data instanceof GltfAssetV2 glb) || glb.getGltf().getNodes() == null) { return false; }
                var names = glb.getGltf().getNodes().stream().filter(node -> node.getMesh() != null)
                      .map(node -> node.getName() == null ? "" : node.getName()).toList();
                // ConnectedKit owns validation. Here only distinguish complete storeys from side-dependent pieces.
                return names.stream().anyMatch(name -> name.matches(".*-base[0-9]+"))
                      && names.stream().noneMatch(name -> name.contains("-link-") || name.contains("-end-"));
            } catch (IOException | RuntimeException failure) {
                LOGGER.warn("Cannot read building kit metadata for {}: {}", path, failure.getMessage());
                return false;
            }
        });
    }

    /** Any authored building side, including a neighbour's incoming side. Null board: the hex's own sides only. */
    public static boolean buildingConnected(Hex hex, Coords at, Function<Coords, Hex> board) {
        if (hex == null || !hex.containsTerrain(Terrains.BUILDING)) { return false; }
        if ((hex.getTerrain(Terrains.BUILDING).getExits() & 63) != 0) { return true; }
        if (board == null || at == null) { return false; }
        for (int direction = 0; direction < 6; direction++) {
            Hex neighbour = board.apply(at.translated(direction));
            if (neighbour != null && neighbour.containsTerrainExit(Terrains.BUILDING, (direction + 3) % 6)) { return true; }
        }
        return false;
    }

    /** Whether this generated building has a connected, overlapping neighbour whose roof stops below its own. */
    public static boolean buildingProfileAvailable(Hex hex, Coords at, Function<Coords, Hex> board) {
        if (hex == null || at == null || hex.getBuildingReference() != null || !hex.containsTerrain(Terrains.BUILDING)) { return false; }
        String asset = structureModels(hex, true).get(Terrains.BUILDING);
        if (asset == null || !generatedKit(asset)) { return false; }
        int top = hex.getLevel() + Math.max(1, hex.terrainLevel(Terrains.BLDG_ELEV));
        for (int direction = 0; direction < 6; direction++) {
            if (!hex.containsTerrainExit(Terrains.BUILDING, direction)) { continue; }
            Hex next = board.apply(at.translated(direction));
            if (next == null || !next.containsTerrainExit(Terrains.BUILDING, (direction + 3) % 6)) { continue; }
            int nextTop = next.getLevel() + Math.max(1, next.terrainLevel(Terrains.BLDG_ELEV));
            if (nextTop > hex.getLevel() && next.getLevel() < top && nextTop < top) { return true; }
        }
        return false;
    }

    /**
     * The standard modular kit is determined by gameplay class and weight, independently of height and connections:
     * {@code buildings/standard/<class>-<weight>}, an ordinary building's class written {@code building}.
     */
    public static String genericBuilding(Hex hex) {
        if (!hex.containsTerrain(Terrains.BUILDING)) { return null; }
        if (hex.terrainLevel(Terrains.BUILDING) == BuildingType.WALL.getTypeValue()) {
            return "buildings/wall-" + (hex.getTerrain(Terrains.BUILDING).getExits() & 63);
        }
        String weight = switch (hex.terrainLevel(Terrains.BUILDING)) {
            case 2 -> "medium";
            case 3 -> "heavy";
            case 4 -> "hard";
            default -> "light";
        };
        String name = className(hex.terrainLevel(Terrains.BLDG_CLASS));
        return "buildings/standard/" + (name == null ? "building" : name) + "-" + weight;
    }

    private static BuildingFile buildingFile(String asset) {
        File root = Configuration.dataDir();
        BuildingFiles files = buildingFiles;
        if (files == null || !files.root().equals(root)) {
            synchronized (TilesetScenery.class) {
                files = buildingFiles;
                if (files == null || !files.root().equals(root)) {
                    buildingFiles = files = new BuildingFiles(root, new ConcurrentHashMap<>());
                }
            }
        }
        File directory = files.root();
        return files.files().computeIfAbsent(asset, key -> buildingFile(directory, key));
    }

    private static BuildingFile buildingFile(File root, String asset) {
        String relative = asset.substring("buildings/".length()).replaceFirst("@.*$", "");
        if (relative.startsWith("saxarba/")) { relative = relative.substring("saxarba/".length()); }
        File exact = new File(root, "models/buildings/" + relative + ".glb");
        File design = new File(root, "models/buildings/" + relative.replaceFirst("_[0-9]{2}$", "") + ".glb");
        // A kit generated from its JSON spec stands for both its footprints (the asset's -square names the Square one),
        // all its variants (@ and a variant's name, kitVariant) and, as the design does, every exit value.
        File generated = new File(root, "models/buildings/"
              + relative.replaceFirst("-square$", "").replaceFirst("_[0-9]{2}$", "") + ".json");
        boolean exists = exact.isFile();
        File custom = exact;
        if (!exists && !design.equals(exact) && design.isFile()) { custom = design; exists = true; }
        if (!exists && generated.isFile()) { custom = generated; exists = true; }
        boolean legacy = new File(root, "models/board/" + asset + ".glb").isFile();
        return new BuildingFile(custom, exists, legacy);
    }

    /** Whether the asset's kit is generated from a JSON spec, which holds both footprints ({@link #customBuildingFile}). */
    public static boolean generatedKit(String asset) {
        return asset.startsWith("buildings/") && customBuildingFile(asset).getName().endsWith(".json");
    }

    /**
     * The building asset for a generated kit's variant {@code name} ({@link HexAppearance#kitVariant}): the asset and
     * {@code @name} for one of its listed variants but the first; the asset itself for the first, none (null), a name
     * the spec does not list, or any other asset, so that all of those draw one kit.
     */
    public static String kitVariant(String asset, String name) {
        return name != null && kitVariants(asset).indexOf(name) > 0 ? asset + "@" + name : asset;
    }

    /** A building class as a kit's spec names it (a variant's {@code class}); null for an ordinary building. */
    private static String className(int buildingClass) {
        return switch (buildingClass) {
            case IBuilding.HANGAR -> "hangar";
            case IBuilding.FORTRESS -> "fortress";
            case IBuilding.GUN_EMPLACEMENT -> "gun-emplacement";
            default -> null;
        };
    }

    /**
     * The variant a building of {@code buildingClass} draws of the generated kit {@code asset}: its {@code stored} one
     * where the kit lists it; else the kit's first variant for its class (a hangar's hall, without the rooms its other
     * variants show) where it has one; else the first (null where the kit lists none).
     */
    public static String drawnVariant(String stored, String asset, int buildingClass) {
        Variants kit = variants(asset);
        if (stored != null && kit.names().contains(stored)) { return stored; }
        String name = className(buildingClass);
        if (name != null && kit.classes().containsKey(name)) { return kit.classes().get(name); }
        return kit.names().isEmpty() ? null : kit.names().getFirst();
    }

    /**
     * A generated kit's spec, read for its variants' names, those that offer a roof that spans their hall, and the first
     * variant for each building class, while the file is unchanged ({@link #kitVariants}, {@link #kitVaults},
     * {@link #drawnVariant}).
     */
    private record Variants(long modified, long length, List<String> names, List<String> vaults, Map<String, String> classes) { }

    private static final Map<File, Variants> VARIANTS = new ConcurrentHashMap<>();

    /**
     * The names of the variants a generated kit's JSON spec lists, in its order (the first is the default); empty for
     * any other asset (null: none) or a spec that cannot be read.
     */
    public static List<String> kitVariants(String asset) { return variants(asset).names(); }

    /**
     * The variants of a generated kit that offer a roof that spans their hall beside their flat one (the spec's
     * {@code vaulted}): a building chooses between the two ({@link HexAppearance.Roof}).
     */
    public static List<String> kitVaults(String asset) { return variants(asset).vaults(); }

    /** Marks a kit's asset, after its variant's name, as drawn under that variant's roof that spans its hall. */
    public static final String VAULTED = "~vaulted";

    /**
     * Whether a building of {@code buildingClass} draws its kit's vaulted roof: a hangar, unless it {@code stored} a flat
     * one. Any other building keeps its variant's own roof.
     */
    public static boolean vaulted(HexAppearance.Roof stored, int buildingClass) {
        return buildingClass == IBuilding.HANGAR && stored != HexAppearance.Roof.FLAT;
    }

    /** {@link #kitVariant} for {@code variant}, under its vaulted roof where that is asked for and the variant has one. */
    public static String roofed(String asset, String variant, boolean vaulted) {
        String drawn = kitVariant(asset, variant);
        if (!vaulted || variant == null || !kitVaults(asset).contains(variant)) { return drawn; }
        return (drawn.contains("@") ? drawn : drawn + "@" + variant) + VAULTED;
    }

    private static final Variants NO_VARIANTS = new Variants(0, 0, List.of(), List.of(), Map.of());

    private static Variants variants(String asset) {
        if (asset == null || !asset.startsWith("buildings/") || !generatedKit(asset)) { return NO_VARIANTS; }
        File spec = customBuildingFile(asset).getAbsoluteFile();
        long modified = spec.lastModified(), length = spec.length();
        Variants known = VARIANTS.get(spec);
        if (known == null || known.modified() != modified || known.length() != length) {
            List<String> names = new ArrayList<>(), vaults = new ArrayList<>();
            Map<String, String> classes = new java.util.HashMap<>();
            try {
                var file = new com.fasterxml.jackson.databind.ObjectMapper().readTree(spec);
                for (var variant : file.path("variants")) {
                    if (variant.hasNonNull("name")) {
                        String name = variant.get("name").asText();
                        names.add(name);
                        if (variant.hasNonNull("vaulted") || file.path("shared").hasNonNull("vaulted")) { vaults.add(name); }
                        if (variant.hasNonNull("class")) { classes.putIfAbsent(variant.get("class").asText(), name); }
                    }
                }
            } catch (IOException failure) {
                LOGGER.warn("Cannot read the variants of {}: {}", spec, failure.getMessage());
            }
            known = new Variants(modified, length, List.copyOf(names), List.copyOf(vaults), Map.copyOf(classes));
            VARIANTS.put(spec, known);
        }
        return known;
    }

    /**
     * The building asset for a {@link HexAppearance.Footprint}: a Square hex's generated kit in its Square footprint
     * (the asset and {@code -square}); any other asset as it is.
     */
    public static String footprint(String asset, HexAppearance.Footprint footprint) {
        if (footprint != HexAppearance.Footprint.SQUARE || !asset.startsWith("buildings/") || !generatedKit(asset)) {
            return asset;
        }
        return asset.endsWith("-square") ? asset : asset + "-square";
    }

    /**
     * The hex's super images (and orthographic ones), each appearance resolved in isolation: an appearance owner's
     * layers come from its designed artwork hex, in place of the hex's own, and unrelated shared-fluff art stays. They are
     * drawn in the tileset's order ({@link TilesetMatcher#order}), as the token the design stands for would be.
     */
    public static List<TilesetMatcher.Pick> appearanceLayers(Hex hex, boolean orthographic) {
        return appearanceLayers(tileset(), hex, orthographic);
    }

    private static List<TilesetMatcher.Pick> appearanceLayers(TilesetMatcher definitions, Hex hex, boolean orthographic) {
        var selection = definitions.select(hex);
        List<TilesetMatcher.Pick> images = new ArrayList<>(selection.supers());
        if (orthographic) { images.addAll(selection.orthographic()); }
        if (hex.getAppearance().isEmpty()) { return images; }
        var blueprint = BoardEditorBlueprint.get();
        for (String owner : hex.getAppearance().keySet().stream().sorted().toList()) {
            Hex query = blueprint.artwork(hex, owner);
            if (query == hex) { continue; }
            List<Integer> types = owner.equals("rough") ? List.of(Terrains.ROUGH)
                  : blueprint.components().stream().filter(c -> c.id().equals(owner)).flatMap(c -> c.fields().stream())
                        .map(f -> Terrains.getType(f.terrain())).toList();
            Predicate<TilesetMatcher.Pick> belongs = image -> types.stream().anyMatch(image::has);
            images.removeIf(belongs);
            var designed = definitions.select(query);
            List<TilesetMatcher.Pick> replacement = new ArrayList<>(designed.supers());
            if (orthographic) { replacement.addAll(designed.orthographic()); }
            replacement.removeIf(belongs.negate());
            images.addAll(replacement);
        }
        images.sort(Comparator.comparingInt(definitions::order));
        return images;
    }

    /**
     * The hex's structure models by structure terrain: a designed building's or fuel tank's model, else its tileset entry;
     * only models that exist for that structure ({@link BoardGenerator#exists}, or a custom kit).
     */
    public static Map<Integer, String> structureModels(Hex hex) {
        return structureModels(hex, buildingConnected(hex, null, null));
    }

    /** Resolves the hex with the board's incoming building connections included in {@code connected}. */
    public static Map<Integer, String> structureModels(Hex hex, boolean connected) {
        return structureModels(tileset(), hex, connected);
    }

    private static Map<Integer, String> structureModels(TilesetMatcher definitions, Hex hex, boolean connected) {
        Map<Integer, String> models = new HashMap<>();
        for (int terrain : new int[] { Terrains.BUILDING, Terrains.FUEL_TANK }) {
            var design = hex.getAppearance().get(terrain == Terrains.BUILDING ? "building" : "fuelTank");
            if (design == null || !hex.containsTerrain(terrain)) { continue; }
            var variant = BoardEditorBlueprint.get().variant(design.variant());
            String asset = design.asset() != null ? design.asset() : variant == null ? null : variant.model(hex);
            if (asset != null && !asset.isBlank() && (BoardGenerator.exists(asset, terrain)
                  || asset.startsWith("buildings/") && buildingFile(asset).customExists())) { models.put(terrain, asset); }
        }
        String generic = genericBuilding(hex);
        // Only an explicit modern design overrides the gameplay weight/class. Old tileset artwork must not
        // displace the modular kit merely because a retired GLB still exists in a user's data directory.
        if (generic != null && (BoardGenerator.exists(generic, Terrains.BUILDING)
              || buildingFile(generic).customExists())) { models.putIfAbsent(Terrains.BUILDING, generic); }
        if (hex.containsAnyTerrainOf(Terrains.BUILDING, Terrains.FUEL_TANK, Terrains.INDUSTRIAL)) {
            List<TilesetMatcher.Pick> images = new ArrayList<>(appearanceLayers(definitions, hex, false));
            images.add(definitions.select(hex).base());
            for (TilesetMatcher.Pick image : images) {
                String model = image == null ? "" : image.key();
                if (model.startsWith("buildings/")) {
                    BuildingFile file = buildingFile(model);
                    boolean glb = file.legacyExists(), custom = file.customExists();
                    // A generated asset counts only for the structure its generator builds (BoardGenerator).
                    var generator = BoardGenerator.of(model);
                    // An entry that names only the key has no art to fall back to.
                    if (!glb && !custom && generator == null && !TilesetMatcher.image(image.source()) && MISSING.add(model)) {
                        LOGGER.warn("The Classic tileset names the structure model {}, which does not exist", model);
                    }
                    for (int terrain : new int[] { Terrains.BUILDING, Terrains.FUEL_TANK, Terrains.INDUSTRIAL }) {
                        boolean drawn = glb || custom || generator != null && generator.builds(terrain);
                        if (drawn && hex.containsTerrain(terrain) && image.has(terrain)) {
                            models.putIfAbsent(terrain, model);
                        }
                    }
                }
            }
        }
        if (connected && singleHexBuilding(models.get(Terrains.BUILDING))) { models.put(Terrains.BUILDING, generic); }
        models.computeIfPresent(Terrains.BUILDING, (terrain, asset) -> {
            String kit = footprint(asset, HexAppearance.footprint(hex.getAppearance()));
            int buildingClass = hex.terrainLevel(Terrains.BLDG_CLASS);
            return roofed(kit, drawnVariant(HexAppearance.kitVariant(hex.getAppearance()), kit, buildingClass),
                  vaulted(HexAppearance.roof(hex.getAppearance()), buildingClass));
        });
        // Walls are a supported building type without legacy building art. The renderer generates their solid mesh.
        if (hex.terrainLevel(Terrains.BUILDING) == megamek.common.enums.BuildingType.WALL.getTypeValue()) {
            models.putIfAbsent(Terrains.BUILDING, "buildings/wall-" + (hex.getTerrain(Terrains.BUILDING).getExits() & 63));
        }
        return Map.copyOf(models);
    }

    /** {@link #resolve(Hex, Map, boolean)} with the hex's own {@link #structureModels}. */
    public static Resolved resolve(Hex hex, boolean nativeBoard) {
        TilesetMatcher definitions = tileset();
        return resolve(definitions, hex, structureModels(definitions, hex, buildingConnected(hex, null, null)), nativeBoard);
    }

    /**
     * The hex's scenery layers, from its original selected layers: matching a hex after removing its roads, woods or
     * buildings would lose composite rules (parked cars, port containers, gardens). A layer is scenery when it matched a
     * {@link #SCENERY_TERRAINS} terrain; pavement variants belong to the ground. A sibling scenery model replaces only its
     * layer; flat art stays paint on the existing surface. On a native board, ROAD level 2 is the Alley finish, not the
     * legacy road-with-trees art. {@code structures} are the hex's {@link #structureModels}.
     */
    public static Resolved resolve(Hex hex, Map<Integer, String> structures, boolean nativeBoard) {
        return resolve(tileset(), hex, structures, nativeBoard);
    }

    private static Resolved resolve(TilesetMatcher definitions, Hex hex, Map<Integer, String> structures,
          boolean nativeBoard) {
        List<Scenery.Model> models = new ArrayList<>(), legacyDecals = new ArrayList<>();
        List<Layer> result = new ArrayList<>();
        Set<Integer> covered = new HashSet<>();
        int cosmeticRoadExits = 0;
        List<TilesetMatcher.Pick> layers = new ArrayList<>(appearanceLayers(definitions, hex, true));
        var base = definitions.select(hex).base();
        if (base != null) { layers.add(base); }
        Set<Integer> queryTypes = new HashSet<>();
        for (int type : hex.getTerrainTypes()) { queryTypes.add(type); }
        if (!hex.getAppearance().isEmpty()) {
            for (String owner : hex.getAppearance().keySet()) {
                for (int type : BoardEditorBlueprint.get().artwork(hex, owner).getTerrainTypes()) {
                    queryTypes.add(type);
                }
            }
        }
        // The kerb and woods art (orchard, container yard) a design stands for is skipped as its token's is: the design
        // draws it (rough reads its design itself).
        Hex road = hex, vegetation = hex;
        if (!hex.getAppearance().isEmpty()) {
            road = BoardEditorBlueprint.get().artwork(hex, "road");
            vegetation = BoardEditorBlueprint.get().artwork(hex, "vegetation");
        }
        for (TilesetMatcher.Pick layer : layers) {
            Set<Integer> types = new HashSet<>();
            for (int type : queryTypes) {
                if (layer.has(type)) { types.add(type); }
            }
            boolean decoration = false;
            for (int type : SCENERY_TERRAINS) { decoration |= types.contains(type); }
            decoration |= !nativeBoard && types.contains(Terrains.ROAD) && hex.terrainLevel(Terrains.ROAD) == 2;
            // Pavement variants belong to the ground; repainting them as scenery hides the native concrete.
            if (!decoration) { continue; }
            covered.addAll(types);
            if (types.stream().anyMatch(structures::containsKey)
                  || types.contains(Terrains.ROAD_FLUFF) && road.terrainLevel(Terrains.ROAD_FLUFF) == 1
                        && types.contains(Terrains.ROAD)
                  || types.contains(Terrains.FLUFF) && (HexTile.roughVariant(hex) != 0
                        || types.contains(Terrains.WOODS) && BoardEditorBlueprint.get().vegetationArt(vegetation))) {
                result.add(new Layer(layer, types, Kind.SKIPPED, null, false));
                continue;
            }
            String key = layer.key();
            // Both GPU views use the fitted quay wall. Its old perspective sprite must not also be
            // projected onto the seabed or baked into the Tactical View's ground artwork.
            if (key.startsWith("quay/")) {
                result.add(new Layer(layer, types, Kind.QUAY, null, false));
                continue;
            }
            // layouts.json is the one legacy key -> mesh table; no mesh sits at a legacy key.
            if (!key.isEmpty() && BoardSceneryLayouts.hasLayout(key)) {
                models.add(new Scenery.Model(key, types));
                // The selected parking sprite specifies a visual route even without gameplay ROAD terrain.
                // These are ordinary hex-direction bits consumed by the existing road engine.
                cosmeticRoadExits |= switch (key) {
                    case "scenery/fluff/cars_1", "scenery/fluff/cars_4" -> 18;
                    case "scenery/fluff/cars_2", "scenery/fluff/cars_5", "scenery/fluff/cars_7" -> 36;
                    case "scenery/fluff/cars_3", "scenery/fluff/cars_6", "scenery/fluff/cars_8" -> 9;
                    default -> 0; // The 2b/3b variants intentionally omit the road.
                };
                result.add(new Layer(layer, types, Kind.MODEL, key, BoardSceneryLayouts.imported(types, hex)));
            } else if (types.contains(Terrains.ROAD) && HexTile.road(hex)) {
                // Road-fluff surface variants use the same material, joins and relief as every other road.
                result.add(new Layer(layer, types, Kind.ROAD_SURFACE, null, false));
            } else {
                boolean legacy = BoardDecalArt.legacy(key) != null;
                if (legacy) { legacyDecals.add(new Scenery.Model(key, types)); }
                result.add(new Layer(layer, types, Kind.PAINT, legacy ? key : null,
                      legacy && BoardSceneryLayouts.imported(types, hex)));
            }
        }
        return new Resolved(result, new Scenery(models, covered, cosmeticRoadExits, legacyDecals));
    }
}
