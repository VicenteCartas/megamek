/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The Assets palette the shipped blueprint declares: categories of sections of cards, decals shown by purpose and again
 * under Decals, merged presets whose levels stay reachable as Brush settings, and the building looks and blueprints.
 */
class BoardEditorBlueprintPaletteTest {
    private static final BoardEditorBlueprint BLUEPRINT = BoardEditorBlueprint.get();

    /** The presets merged into a kept card: deleted key, its terrain then, the card that reaches it now. */
    private static final String[][] MERGED = {
        { "ground/quicksand", "swamp:2", "ground/swamp" },
        { "pavement/2", "pavement:2", "pavement/1" }, { "pavement/3", "pavement:3", "pavement/1" },
        { "pavement/4", "pavement:4", "pavement/1" }, { "rough/2", "rough:2", "rough/1" },
        { "rubble/rubble-medium", "rubble:2", "rubble/rubble-light" }, { "rubble/rubble-heavy", "rubble:3", "rubble/rubble-light" },
        { "rubble/rubble-hardened", "rubble:4", "rubble/rubble-light" }, { "rubble/rubble-wall", "rubble:5", "rubble/rubble-light" },
        { "rubble/rubble-ultra", "rubble:6", "rubble/rubble-light" },
        { "vegetation/woods-2", "woods:2;foliage_elev:2", "vegetation/woods-1" },
        { "vegetation/woods-3", "woods:3;foliage_elev:3", "vegetation/woods-1" },
        { "vegetation/low-woods", "woods:1;foliage_elev:1", "vegetation/woods-1" },
        { "vegetation/jungle-2", "jungle:2;foliage_elev:2", "vegetation/jungle-1" },
        { "vegetation/jungle-3", "jungle:3;foliage_elev:3", "vegetation/jungle-1" },
        { "vegetation/low-jungle", "jungle:1;foliage_elev:1", "vegetation/jungle-1" },
        { "water/deep", "water:2", "water/shallow" }, { "water/rapids", "water:1;rapids:1", "water/shallow" },
        { "water/torrent", "water:1;rapids:2", "water/shallow" },
        { "road/2", "road:2", "road/1" }, { "road/3", "road:3", "road/1" }, { "road/4", "road:4", "road/1" },
        { "weather/snow-deep", "snow:2", "weather/snow-thin" }, { "hazards/inferno", "fire:2", "hazards/fire" },
        { "hazards/smoke-heavy", "smoke:2", "hazards/smoke-light" }, { "hazards/magma", "magma:2", "hazards/magma-crust" },
        { "hazards/geyser-active", "geyser:2", "hazards/geyser-dormant" },
        { "hazards/geyser-magma", "geyser:3", "hazards/geyser-dormant" },
    };

    @Test
    void theShippedPaletteHasTenCategoriesAndShowsEveryEntryInEachOfItsSections() {
        assertEquals(List.of("theme", "ground-water", "vegetation", "transportation", "buildings", "industry", "parks", "props",
              "hazards", "decals"), BLUEPRINT.categories().stream().map(BoardEditorBlueprint.Category::id).toList());
        assertEquals(List.of("dirt", "rock"), BLUEPRINT.specialThemes());
        for (var category : BLUEPRINT.categories()) {
            assertNotNull(category.icon(), category.id());
            assertTrue(category.themes() != null || !category.sections().isEmpty(), category.id());
            category.sections().forEach(section -> assertFalse(section.cards().isEmpty(), "Empty section " + section.path()));
        }
        List<String> keys = new ArrayList<>();
        BLUEPRINT.components().forEach(c -> c.presets().stream().filter(p -> !p.sections().isEmpty())
              .forEach(p -> keys.add(c.id() + "/" + p.id())));
        HexAppearance.OWNERS.forEach(owner -> BLUEPRINT.variants(owner).stream().filter(v -> !v.sections().isEmpty())
              .forEach(v -> keys.add(v.id())));
        BLUEPRINT.assets().stream().filter(a -> !a.sections().isEmpty()).forEach(a -> keys.add(a.id()));
        for (String key : keys) {
            var entry = BLUEPRINT.entry(key);
            assertNotNull(entry, key);
            var card = BLUEPRINT.card(entry);
            assertTrue(card.members().contains(entry), key);
            for (String path : entry.sections()) {
                assertTrue(section(path).cards().contains(card), key + " shows in " + path);
            }
        }
        assertNull(BLUEPRINT.entry("hex/sample"));
        // An automatic building piece is no card: the 3D board picks it from weight and exits.
        assertNull(BLUEPRINT.entry("buildings/saxarba/building_light/building_light_12"));
    }

    /** The crystal props are behind Props' Crystals card, by their kind of crystal; the cover skin stays Vegetation's card. */
    @Test
    void theCrystalPropsAreASubcategoryApartFromTheCoverSkin() {
        var props = BLUEPRINT.categories().stream().filter(c -> c.id().equals("props")).findFirst().orElseThrow();
        assertEquals(List.of("crystals", "animals"), props.subcategories());
        assertEquals(List.of("general"), props.sections(null).stream().map(BoardEditorBlueprint.Section::id).toList());
        var crystals = props.sections("crystals").stream().flatMap(section -> section.cards().stream())
              .flatMap(card -> card.members().stream()).toList();
        assertEquals(15, crystals.size());
        assertTrue(crystals.stream().allMatch(entry -> entry.asset() != null && entry.asset().kind().equals("prop")), "Props only");
        assertEquals(List.of("vegetation/gameplay", "vegetation/looks"), BLUEPRINT.entry("woods/crystal").sections());
    }

    /** Ground & water's Liquids: the Water card and the Lava card, liquid magma on the water's depth. */
    @Test
    void theLiquidsSectionHoldsWaterAndLava() {
        assertEquals(List.of("basic", "gameplay", "liquids"), BLUEPRINT.categories().get(1).sections().stream()
              .map(BoardEditorBlueprint.Section::id).toList());
        assertEquals(List.of("water/shallow", "water/magma"), section("ground-water/liquids").cards().stream()
              .map(card -> card.members().getFirst().key()).toList());
        Hex lava = new Hex(0, BLUEPRINT.entry("water/magma").preset().terrain(), "");
        assertEquals("Lava", BLUEPRINT.entry("water/magma").label());
        assertTrue(HexTile.liquid(lava) && lava.terrainLevel(Terrains.MAGMA) == 2 && lava.terrainLevel(Terrains.WATER) == 1);
    }

    @Test
    void everyDecalIsListedUnderDecalsAndAtMostOnceMoreByItsPurpose() {
        for (var decal : BLUEPRINT.assets().stream().filter(a -> !a.sections().isEmpty() && a.kind().equals("decal")).toList()) {
            long underDecals = decal.sections().stream().filter(path -> path.startsWith("decals/")).count();
            assertEquals(1, underDecals, decal.id() + " " + decal.sections());
            assertTrue(decal.sections().size() <= 2, decal.id());
        }
        // The same entry in two places is one card (car-park bays: Vehicles and parking, and Decals).
        var bays = BLUEPRINT.card(BLUEPRINT.entry("decal/car-park/straight-4"));
        assertEquals("Car park bays", bays.label());
        assertTrue(section("transportation/parking").cards().contains(bays) && section("decals/road").cards().contains(bays));
    }

    @Test
    void aMergedPresetStaysReachableThroughTheBrushSettingsOfItsCard() {
        for (String[] merged : MERGED) {
            String owner = merged[0].substring(0, merged[0].indexOf('/'));
            var component = BLUEPRINT.component(owner);
            assertTrue(component.presets().stream().noneMatch(p -> (owner + "/" + p.id()).equals(merged[0])), merged[0]);
            var kept = BLUEPRINT.entry(merged[2]).preset();
            Hex before = new Hex(0, merged[1], ""), now = new Hex(0, kept.terrain(), "");
            for (int type : before.getTerrainTypes()) {
                assertTrue(component.fields().stream().anyMatch(f -> Terrains.getType(f.terrain()) == type), merged[0]);
            }
            for (var field : component.fields()) {
                int type = Terrains.getType(field.terrain()), level = before.terrainLevel(type);
                if (level == now.terrainLevel(type)) { continue; }
                assertTrue(field.brush() || kept.settings().contains(field.terrain()), merged[0] + ": " + field.terrain());
                if (level == Terrain.LEVEL_NONE || merged[0].equals("pavement/4")) { continue; } // Runway is Concrete's alias
                assertTrue(field.choices().isEmpty() ? level >= field.min() && level <= field.max()
                      : field.choices().stream().anyMatch(c -> c.value() == level), merged[0] + ": " + field.terrain());
            }
        }
    }

    /**
     * The container yard (b12): one gallery of five looks, first in Industry > Seaport and third in Vegetation > Looks,
     * beside the quay art and seven prop cards; the legacy seaport pieces leave the palette but keep their labels for old
     * objects.
     */
    @Test
    void theContainerYardIsOneGalleryInSeaportAndLooksBesideTheSeaportProps() {
        var seaport = section("industry/seaport").cards();
        assertEquals(List.of("Container yard", "Legacy quay art", "Container", "Quay crane", "Crane boom segment", "Yard gantry",
              "Straddle carrier", "Reach stacker", "Yard truck"), seaport.stream().map(BoardEditorBlueprint.Card::label).toList());
        var yard = seaport.getFirst();
        assertTrue(yard.gallery());
        assertEquals(5, yard.members().size());
        assertTrue(yard.members().stream().allMatch(member -> member.variant().skin() != null && member.owner().equals("vegetation")));
        assertEquals(yard, section("vegetation/looks").cards().get(2));
        assertTrue(seaport.get(2).picker() && seaport.get(5).picker());
        // The Industry tile shows the gantry yard look turned across the view.
        assertEquals(new HexAppearance("woods/container-yard/yard-gantry", null, null, null, BoardDecoration.Colours.NONE, 90),
              BLUEPRINT.categories().stream().filter(c -> c.id().equals("industry")).findFirst().orElseThrow().icon()
                    .getAppearance().get("vegetation"));
    }

    @Test
    void fuelTanksBelongOnlyToIndustryAndObsoleteIndustrialCardsAreAbsent() {
        assertEquals(List.of("industry/gameplay"), BLUEPRINT.entry("fuelTank/default").sections());
        assertNull(BLUEPRINT.entry("building/tank-farm"), "One fuel-tank card, under Industry");
        assertEquals("fuelTank", BLUEPRINT.variant("building/tank-farm").owner());
        assertTrue(BLUEPRINT.variants("building").stream().noneMatch(variant -> variant.asset().contains("/fuel_tanks/")));
        // Heavy industry is a terrain whose plant is generated (BoardGenerator); no building design names its plants.
        for (String suffix : List.of("a", "b", "c", "d")) {
            String id = "buildings/saxarba/misc/heavy_industrial_" + suffix;
            assertNull(BLUEPRINT.entry(id), "The obsolete industrial design is not a palette card");
            assertNull(BLUEPRINT.variant(id), "Nor a design");
        }
    }

    @Test
    void domesAndTankFarmsUseTheCurrentGeneratorsAndKits() {
        Hex sealed = new Hex(0, "building:2:12", "");
        sealed.setAppearance(java.util.Map.of("building", new HexAppearance("building/glass-dome", null, null, null)));
        assertEquals("buildings/multihex/dome", TilesetScenery.structureModels(sealed).get(Terrains.BUILDING));
        assertEquals(BuildingInteriors.NO, BuildingInteriors.resolve(sealed));
        for (int weight = 1; weight <= 4; weight++) {
            String model = BLUEPRINT.variant("building/tank-farm").model(new Hex(0, "fuel_tank:" + weight + ":9", ""));
            assertTrue(TilesetScenery.customBuildingFile(model).isFile(), model);
        }
    }

    @Test
    void buildingClassesAndWallsHaveACardPerWeight() {
        var editor = new megamek.client.ui.boardeditor.BoardEditorSession();
        int[] classes = { 0, 2, 1, 3 };
        List<String> sections = List.of("standard", "fortresses", "hangars", "gun-emplacements");
        for (int family = 0; family < sections.size(); family++) {
            var cards = section("buildings/" + sections.get(family)).cards();
            assertEquals(List.of("Civilian", "Light", "Medium", "Heavy", "Hardened", "Castle Brian"),
                  cards.stream().map(BoardEditorBlueprint.Card::label).toList());
            for (int weight = 0; weight <= 5; weight++) {
                var entry = cards.get(weight).members().getFirst();
                editor.command(new megamek.client.ui.boardeditor.BoardEditorSession.Command(
                      megamek.client.ui.boardeditor.BoardEditorSession.Action.CHOOSE_BRUSH, "building", entry.preset().id()), null);
                Coords at = new Coords(weight * 2, family * 2);
                editor.pointer(at, 0, 0, false); editor.finishStroke();
                Hex painted = editor.board().getHex(at);
                assertEquals(TilesetScenery.rulesWeight(weight), painted.terrainLevel(Terrains.BUILDING));
                assertEquals(weight, TilesetScenery.buildingWeight(painted));
                assertTrue(new java.io.File(megamek.common.Configuration.dataDir(), "models/" + TilesetScenery.genericBuilding(painted) + ".json")
                      .isFile(), "Every class and weight has its kit: " + TilesetScenery.genericBuilding(painted));
                assertEquals(classes[family], painted.terrainLevel(Terrains.BLDG_CLASS));
            }
        }
        var walls = section("buildings/walls").cards();
        assertEquals(List.of("Civilian", "Light", "Medium", "Heavy", "Hardened", "Castle Brian"),
              walls.stream().map(BoardEditorBlueprint.Card::label).toList());
        for (var card : walls) {
            Hex wall = new Hex(0, card.members().getFirst().preset().terrain(), "");
            assertEquals(5, wall.terrainLevel(Terrains.BUILDING));
            assertEquals(BuildingInteriors.NO, BuildingInteriors.resolve(wall));
        }
        assertEquals(List.of("Lunar habitat", "Glass dome", "Biodome"), section("buildings/looks").cards().stream()
              .map(BoardEditorBlueprint.Card::label).toList());
        var buildings = BLUEPRINT.categories().stream().filter(category -> category.id().equals("buildings")).findFirst().orElseThrow();
        assertEquals(List.of("civic", "utopia", "frontier", "imperial", "heritage"), buildings.subcategories("looks"), "The modular looks open from Looks");
        for (String look : List.of("Civic", "Utopia", "Frontier", "Imperial", "Heritage")) {
            assertEquals(look + " (Modular)", section("buildings/" + look.toLowerCase()).cards().getFirst().label());
        }
        assertEquals(8, BLUEPRINT.variants("building").size(), "Only current looks");
    }

    @Test
    void everyDesignAndObjectOfTheShippedNativeBoardsResolves() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(Configuration.boardsDir().toPath())) {
            files = walk.filter(path -> BoardFile.isNativeName(path.toString())).toList();
        }
        assertFalse(files.isEmpty());
        // A bridge's Pillars toggle is stored as a design of its own, with no blueprint variant; so are the implicit
        // bridge a road or rail makes for itself (BridgeSpan.carry), a rail junction's track layout and a connected
        // building's Square footprint.
        var pillars = Set.of(HexAppearance.PILLARS.variant(), HexAppearance.TRUSS_PILLARS.variant(),
              HexAppearance.IMPLICIT_BRIDGE.variant(), HexAppearance.IMPLICIT_PILLARS.variant(),
              HexAppearance.tracks(1).variant(), HexAppearance.SQUARE.variant());
        for (Path file : files) {
            Board board = BoardFile.read(file, true);
            for (int y = 0; y < board.getHeight(); y++) {
                for (int x = 0; x < board.getWidth(); x++) {
                    Hex hex = board.getHex(x, y);
                    hex.getAppearance().values().stream().filter(design -> design.variant() != null && !pillars.contains(design.variant()))
                          .forEach(design -> assertNotNull(BLUEPRINT.variant(design.variant()), file + ": " + design.variant()));
                    hex.getDecorations().forEach(object -> assertNotNull(BLUEPRINT.asset(object.asset()), file + ": " + object.asset()));
                }
            }
        }
    }

    /**
     * An unknown section, a one-member family, a family across two sections, a key on the palette twice (a preset's
     * "owner/id" and a variant id) and a setting that is no field of the preset's component.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "Unknown palette section | 'assets': [{'id': 'tree', 'label': 'Tree', 'kind': 'prop', 'section': 'c/x'}]",
        "Family F | 'assets': [{'id': 'tree', 'label': 'Tree', 'kind': 'prop', 'section': 'c/s', 'family': 'F'}]",
        "Family F | 'assets': [{'id': 'tree', 'label': 'Tree', 'kind': 'prop', 'section': 'c/s', 'family': 'F'},"
              + "{'id': 'pine', 'label': 'Pine', 'kind': 'prop', 'section': ['c/s', 'c/t'], 'family': 'F'}]",
        "Duplicate palette entry | 'components': [{'id': 'ground', 'label': 'G', 'presets': [{'id': 'x', 'label': 'X',"
              + " 'section': 'c/s'}]}], 'variants': [{'id': 'ground/x', 'label': 'X', 'owner': 'ground', 'section': 'c/s'}]",
        "is no field | 'components': [{'id': 'ground', 'label': 'G', 'presets': [{'id': 'x', 'label': 'X', 'settings': ['water']}]}]",
    })
    void aBrokenPaletteIsRefused(String reason, String content, @TempDir Path directory) throws IOException {
        Path file = Files.writeString(directory.resolve("blueprint.json"), ("{'format': 'megamek-editor-blueprint', 'version': 1,"
              + " 'categories': [{'id': 'c', 'icon': {'theme': 'grass'}, 'sections': ['s', 't']}], " + content + "}")
              .replace('\'', '"'));
        var failure = assertThrows(IOException.class, () -> BoardEditorBlueprint.read(file));
        assertTrue(failure.getMessage().contains(reason), failure.getMessage());
    }

    private static BoardEditorBlueprint.Section section(String path) {
        return BLUEPRINT.categories().stream().flatMap(c -> c.sections().stream()).filter(s -> s.path().equals(path))
              .findFirst().orElseThrow(() -> new AssertionError("No section " + path));
    }
}
