/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import megamek.common.Hex;
import megamek.common.IndustrialElevator;
import megamek.common.enums.BasementType;
import megamek.common.enums.BuildingType;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/**
 * Version 3's named components at the file boundary. The game's Hex/Terrain model remains authoritative. This fixed
 * schema deliberately does not depend on the editor palette, installed artwork, or translated labels.
 */
final class BoardComponents {
    private static final List<String> DIRECTIONS = List.of("n", "ne", "se", "s", "sw", "nw");
    private static final Map<String, List<Field>> COMPONENTS = schema();

    private record Field(String name, int terrain, String connections) { }

    private BoardComponents() { }

    private static Map<String, List<Field>> schema() {
        Map<String, List<Field>> result = new LinkedHashMap<>();
        fields(result, "building", "type=building", "cf=bldg_cf", "height=bldg_elev", "class=bldg_class",
              "basement=bldg_basement_type", "armor=bldg_armor", "basementCollapsed=bldg_base_collapsed", "legacyArt=bldg_fluff",
              "interiors=bldg_interiors");
        fields(result, "bridge", "weight=bridge", "cf=bridge_cf", "height=bridge_elev", "mud=bridge_mud");
        fields(result, "fuelTank", "weight=fuel_tank", "cf=fuel_tank_cf", "height=fuel_tank_elev", "magnitude=fuel_tank_magn");
        fields(result, "road", "type=road", "legacyArt=road_fluff");
        fields(result, "bridgeRoad", "type=bridge_road");
        fields(result, "rail", "type=rail");
        fields(result, "bridgeRail", "type=bridge_rail");
        fields(result, "water", "depth=water", "rapids=rapids", "legacyArt=water_fluff");
        fields(result, "vegetation", "woods=woods", "jungle=jungle", "height=foliage_elev");
        fields(result, "ground", "sand=sand", "tundra=tundra", "fields=planted_fields", "swamp=swamp", "mud=mud", "legacyArt=ground_fluff");
        fields(result, "pavement", "level=pavement");
        fields(result, "rough", "level=rough");
        fields(result, "rubble", "type=rubble");
        fields(result, "industry", "height=heavy_industrial");
        fields(result, "cliff", "level=cliff_top");
        fields(result, "weather", "ice=ice", "snow=snow", "blackIce=black_ice");
        fields(result, "hazards", "fire=fire", "smoke=smoke", "magma=magma", "geyser=geyser",
              "hazardousLiquid=hazardous_liquid", "ultraSublevel=ultra_sublevel");
        fields(result, "scenario", "impassable=impassable", "fortified=fortified", "metalDeposit=metal_deposit", "deploymentZones=deployment_zone");
        fields(result, "salvage", "arms=arms", "legs=legs");
        fields(result, "elevators", "solaris=solaris_elevator", "industrial=industrial_elevator");
        fields(result, "space", "level=space", "screen=screen");
        fields(result, "legacyAppearance", "fluff=fluff", "none=none");
        fields(result, "scorch");
        return java.util.Collections.unmodifiableMap(result);
    }

    private static void fields(Map<String, List<Field>> components, String component, String... names) {
        List<Field> fields = new ArrayList<>();
        for (String pair : names) {
            String[] parts = pair.split("=");
            int type = Terrains.getType(parts[1]);
            String connections = switch (type) {
                case Terrains.BUILDING, Terrains.FUEL_TANK, Terrains.BRIDGE, Terrains.ROAD, Terrains.BRIDGE_ROAD,
                      Terrains.RAIL, Terrains.BRIDGE_RAIL, Terrains.CLIFF_TOP -> "connections";
                default -> null;
            };
            fields.add(new Field(parts[0], type, connections));
        }
        components.put(component, List.copyOf(fields));
    }

    /** Values with game-defined names. Unknown numeric values survive editor repair and legacy imports unchanged. */
    private static List<String> names(int terrain) {
        return switch (terrain) {
            case Terrains.BUILDING, Terrains.BRIDGE, Terrains.FUEL_TANK -> List.of("", "light", "medium", "heavy", "hardened");
            // A building's or wall's weight (TilesetScenery#buildingWeight).
            case Terrains.BLDG_WEIGHT -> List.of("civilian", "light", "medium", "heavy", "hardened", "castleBrian");
            case Terrains.BLDG_CLASS -> List.of("standard", "hangar", "fortress", "gunEmplacement");
            case Terrains.BLDG_INTERIORS -> List.of("default", "yes", "noFloors", "no");
            case Terrains.BLDG_BASEMENT_TYPE -> List.of("unknown", "none", "twoDeepFeet", "oneDeepFeet",
                  "oneDeepNormal", "oneDeepInfantry", "oneDeepHead", "twoDeepHead");
            case Terrains.ROAD, Terrains.BRIDGE_ROAD -> List.of("", "paved", "alley", "dirt", "gravel");
            case Terrains.RAIL, Terrains.BRIDGE_RAIL -> List.of("", "rail", "maglev");
            case Terrains.WOODS, Terrains.JUNGLE -> List.of("", "light", "heavy", "ultra");
            case Terrains.ROUGH -> List.of("", "normal", "ultra");
            default -> List.of();
        };
    }

    /** Writes rule components and folds the internal owner map into their appearance; decorations stay unchanged. */
    static void write(Hex hex, ObjectNode node) {
        node.remove("terrain");
        node.remove("ruins");
        JsonNode appearances = node.remove("appearance");
        ObjectNode components = node.putObject("components");
        COMPONENTS.forEach((owner, fields) -> {
            ObjectNode component = object();
            boolean closed = owner.equals("building") && hex.containsTerrain(Terrains.BUILDING)
                  && BuildingInteriors.resolve(hex) == BuildingInteriors.NO;
            // A destroyed structure keeps its component, marked destroyed: its type is a ruin (Hex#getRuins); what the
            // collapse left on the hex (a building's class or armour) is written as it is.
            boolean destroyed = !fields.isEmpty() && !hex.containsTerrain(fields.getFirst().terrain())
                  && hex.getRuin(fields.getFirst().terrain()) != null;
            if (destroyed) { component.put("destroyed", true); }
            for (Field field : fields) {
                Terrain terrain = hex.getTerrain(field.terrain());
                if (terrain == null && destroyed) { terrain = hex.getRuin(field.terrain()); }
                if (closed && field.terrain() == Terrains.BLDG_BASEMENT_TYPE) {
                    terrain = new Terrain(Terrains.BLDG_BASEMENT_TYPE, BasementType.NONE.ordinal());
                }
                if (closed && field.terrain() == Terrains.BLDG_BASE_COLLAPSED) { continue; }
                if (terrain == null) { continue; }
                if (writeSpecial(component, field, terrain)) { continue; }
                var names = names(field.terrain());
                int level = terrain.getLevel();
                if (field.terrain() == Terrains.BUILDING) {
                    boolean wall = level == BuildingType.WALL.getTypeValue();
                    component.put("type", wall ? "wall" : "building");
                    var weights = names(Terrains.BLDG_WEIGHT);
                    int weight = TilesetScenery.buildingWeight(level, hex);
                    if (weight >= 0 && weight < weights.size()) { component.put("weight", weights.get(weight)); }
                    else { component.put("weight", weight); }
                }
                // Rubble is what fell: a building of a weight (levels 1 to 4), a wall (5) or ultra rubble (6).
                else if (field.terrain() == Terrains.RUBBLE && level >= 1 && level <= 6) {
                    component.put("type", level == 5 ? "wall" : level == 6 ? "ultra" : "building");
                    if (level < 5) { component.put("weight", names(Terrains.BUILDING).get(level)); }
                }
                else if (level >= 0 && level < names.size() && !names.get(level).isEmpty()) { component.put(field.name(), names.get(level)); }
                else { component.put(field.name(), level); }
                if (field.connections() != null && (terrain.getExits() & ~63) == 0) {
                    putDirections(component.putArray(field.connections()), terrain.getExits());
                } else if (terrain.hasExitsSpecified()) {
                    // Some legacy selectors (and Solaris elevator die rolls) reuse the exits integer as data.
                    component.withObject("/legacyExits").put(field.name(), terrain.getExits());
                }
            }
            // Water's own appearance, its separated sides, says nothing once the water is gone.
            if (appearances != null && appearances.has(owner) && (!owner.equals("water") || !component.isEmpty())) {
                component.set("appearance", appearances.get(owner).deepCopy());
            }
            if (owner.equals("building") && appearances != null) { buildingAppearance(component, appearances); }
            // A fortified hex's strongpoint (HexAppearance.Fortification), said only while the hex is fortified.
            if (owner.equals("scenario") && component.has("fortified") && appearances != null && appearances.has("fortification")) {
                String look = appearances.get("fortification").path("variant").asText();
                component.withObject("/appearance").put("fortification", look.substring("fortification/".length()));
            }
            if (!component.isEmpty()) { components.set(owner, component); }
        });
        if (components.isEmpty()) { node.remove("components"); }
    }

    private static void buildingAppearance(ObjectNode component, JsonNode appearances) {
        if (!component.has("appearance") && !appearances.has("footprint") && !appearances.has("kitVariant")
              && !appearances.has("buildingProfile") && !appearances.has("doors") && !appearances.has("roof")) { return; }
        ObjectNode style = component.withObject("/appearance");
        if (appearances.has("doors")) {
            // Each door's height in levels by its hexside, and the link group of those that have one
            // (HexAppearance#doors, #doorLinks).
            var stored = Map.of("doors", new HexAppearance(appearances.get("doors").path("variant").asText(), null, null, null));
            int[] heights = HexAppearance.doors(stored), groups = HexAppearance.doorLinks(stored);
            ObjectNode doors = style.putObject("doors");
            for (int side = 0; side < 6; side++) {
                if (heights[side] > 0) { doors.put(DIRECTIONS.get(side), heights[side]); }
            }
            for (int side = 0; side < 6; side++) {
                if (heights[side] > 0 && groups[side] > 0) { style.withObject("/doorLinks").put(DIRECTIONS.get(side), groups[side]); }
            }
        }
        if (appearances.has("footprint")) {
            String footprint = appearances.get("footprint").path("variant").asText();
            style.put("footprint", footprint.substring("footprint/".length()));
        }
        if (appearances.has("kitVariant")) { style.set("kitVariant", appearances.get("kitVariant").get("variant")); }
        if (appearances.has("roof")) { style.put("roof", appearances.get("roof").path("variant").asText().substring("roof/".length())); }
        if (appearances.has("buildingProfile")) { style.put("profile", "stepped"); }
    }

    /** Reads gameplay components into the real hex and returns the owner map consumed by the existing visual reader. */
    static ObjectNode read(JsonNode components, Hex hex, boolean visuals) {
        ObjectNode appearances = object();
        if (components == null) { return appearances; }
        require(components.isObject(), "components must be an object");
        COMPONENTS.forEach((owner, fields) -> {
            JsonNode component = components.get(owner);
            if (component == null) { return; }
            require(component.isObject(), "Component " + owner + " must be an object");
            JsonNode legacy = component.get("legacyExits");
            if (legacy != null) {
                require(legacy.isObject(), owner + ".legacyExits must be an object");
            }
            // A destroyed structure's terrains are its ruin's, not the hex's (Hex#getRuins).
            boolean destroyed = component.path("destroyed").asBoolean(false);
            require(!destroyed || owner.equals("building") || owner.equals("bridge") || owner.equals("fuelTank")
                  || owner.equals("bridgeRoad") || owner.equals("bridgeRail"), owner + " cannot be destroyed");
            for (Field field : fields) {
                boolean connections = field.connections() != null && component.has(field.connections());
                if (!component.has(field.name())) { continue; }
                boolean raw = legacy != null && legacy.has(field.name());
                if (readSpecial(component, field, hex, legacy)) { continue; }
                int level;
                if (field.terrain() == Terrains.BUILDING) {
                    int weight = value(component.path("weight").isMissingNode() ? JsonNodeFactory.instance.textNode("light")
                          : component.get("weight"), Terrains.BLDG_WEIGHT, "building.weight");
                    boolean wall = component.path("type").asText().equals("wall");
                    // A building's type is its weight as the rules know it; a number past the weights is a type of its own.
                    level = wall ? BuildingType.WALL.getTypeValue() : weight <= 5 ? TilesetScenery.rulesWeight(weight) : weight;
                    if (wall || level != weight) { hex.addTerrain(new Terrain(Terrains.BLDG_WEIGHT, weight)); }
                } else if (field.terrain() == Terrains.RUBBLE && component.get("type").isTextual()) {
                    String type = component.get("type").textValue();
                    require(List.of("building", "wall", "ultra").contains(type), "Unknown value for rubble.type: " + type);
                    level = type.equals("wall") ? 5 : type.equals("ultra") ? 6
                          : value(component.path("weight").isMissingNode() ? JsonNodeFactory.instance.textNode("light")
                                : component.get("weight"), Terrains.BUILDING, "rubble.weight");
                } else { level = value(component.get(field.name()), field.terrain(), owner + "." + field.name()); }
                int exits = raw ? integer(legacy.get(field.name()), owner + ".legacyExits." + field.name())
                      : connections ? directions(component.get(field.connections()), owner + "." + field.connections()) : 0;
                // Native connection fields describe the saved topology, including an explicit isolated structure.
                var terrain = new Terrain(field.terrain(), level, raw || field.connections() != null, exits);
                if (destroyed && Hex.structureTerrain(field.terrain())) { hex.addRuin(terrain); } else { hex.addTerrain(terrain); }
            }
            if (visuals && HexAppearance.OWNERS.contains(owner) && component.has("appearance")) {
                require(component.get("appearance").isObject(), owner + ".appearance must be an object");
                ObjectNode style = component.get("appearance").deepCopy();
                if (owner.equals("building")) { readBuildingAppearance(style, appearances); }
                if (!style.isEmpty()) { appearances.set(owner, style); }
            }
            if (visuals && owner.equals("scenario") && component.has("appearance")) {
                JsonNode look = component.get("appearance").get("fortification");
                require(look == null || look.isTextual() && (look.asText().equals("dug") || look.asText().equals("sandbags")),
                      "scenario.appearance.fortification must be dug or sandbags");
                if (look != null && look.asText().equals("sandbags")) {
                    appearances.putObject("fortification").put("variant", HexAppearance.SANDBAGGED.variant());
                }
            }
            if (!visuals && owner.equals("building") && BuildingInteriors.choice(hex) == BuildingInteriors.DEFAULT) {
                var style = component.path("appearance");
                if (BuildingInteriors.defaultFor(hex.terrainLevel(Terrains.BLDG_CLASS),
                      style.path("variant").asText(null), style.path("asset").asText(null)) == BuildingInteriors.NO) {
                    BuildingInteriors.set(hex, BuildingInteriors.NO);
                }
            }
        });
        return appearances;
    }

    private static void readBuildingAppearance(ObjectNode style, ObjectNode appearances) {
        JsonNode profile = style.remove("profile");
        if (profile != null) {
            require(profile.isTextual() && (profile.asText().equals("straight") || profile.asText().equals("stepped")),
                  "building.appearance.profile must be straight or stepped");
            if (profile.asText().equals("stepped")) { appearances.putObject("buildingProfile").put("variant", "building/stepped"); }
        }
        JsonNode footprint = style.remove("footprint");
        if (footprint != null && "square".equals(footprint.asText())) {
            appearances.putObject("footprint").put("variant", "footprint/square");
        }
        JsonNode doors = style.remove("doors");
        if (doors != null) {
            require(doors.isObject(), "building.appearance.doors must be an object of heights by direction");
            int[] heights = new int[6];
            doors.fields().forEachRemaining(door -> {
                int side = DIRECTIONS.indexOf(door.getKey());
                require(side >= 0, "Unknown direction in building.appearance.doors: " + door.getKey());
                heights[side] = integer(door.getValue(), "building.appearance.doors." + door.getKey());
                require(heights[side] >= 1, "building.appearance.doors." + door.getKey() + " must be at least one level");
            });
            int[] groups = new int[6];
            JsonNode links = style.remove("doorLinks");
            if (links != null) {
                require(links.isObject(), "building.appearance.doorLinks must be an object of link groups by direction");
                links.fields().forEachRemaining(link -> {
                    int side = DIRECTIONS.indexOf(link.getKey());
                    require(side >= 0 && heights[side] > 0, "building.appearance.doorLinks names no door: " + link.getKey());
                    groups[side] = integer(link.getValue(), "building.appearance.doorLinks." + link.getKey());
                    require(groups[side] >= 1, "building.appearance.doorLinks." + link.getKey() + " must be a group from 1 up");
                });
            }
            HexAppearance stored = HexAppearance.ofDoors(heights, groups);
            if (stored != null) { appearances.putObject("doors").put("variant", stored.variant()); }
        }
        JsonNode variant = style.remove("kitVariant");
        if (variant != null) {
            require(variant.isTextual(), "building.appearance.kitVariant must be a string");
            appearances.putObject("kitVariant").set("variant", variant);
        }
        JsonNode roof = style.remove("roof");
        if (roof != null) {
            require(roof.isTextual() && List.of("vaulted", "flat").contains(roof.asText()), "building.appearance.roof must be vaulted or flat");
            appearances.putObject("roof").put("variant", "roof/" + roof.asText());
        }
    }

    /** The old exits integer is data, not directions, for elevators and deployment zones. */
    private static boolean writeSpecial(ObjectNode component, Field field, Terrain terrain) {
        int type = field.terrain(), exits = terrain.getExits();
        if (type == Terrains.BLDG_BASEMENT_TYPE && terrain.getLevel() == BasementType.UNKNOWN.ordinal()) { return true; }
        if (type == Terrains.DEPLOYMENT_ZONE) {
            if (terrain.getLevel() == 0 && exits >= 0) { indices(component, field.name(), exits, 31); }
            else {
                component.put(field.name(), terrain.getLevel());
                component.withObject("/legacyExits").put(field.name(), exits);
            }
            return true;
        }
        if (type != Terrains.INDUSTRIAL_ELEVATOR && type != Terrains.SOLARIS_ELEVATOR) { return false; }
        ObjectNode elevator = component.putObject(field.name());
        if (type == Terrains.SOLARIS_ELEVATOR) {
            elevator.put("alternateElevation", terrain.getLevel());
            if ((exits & ~63) == 0) { indices(elevator, "rolls", exits, 6); }
            else { component.withObject("/legacyExits").put(field.name(), exits); }
        } else {
            elevator.put("bottom", terrain.getLevel());
            int top = IndustrialElevator.shaftTop(exits), capacity = IndustrialElevator.capacityTons(exits);
            if (exits == IndustrialElevator.exits(top, capacity)) { elevator.put("top", top).put("capacity", capacity); }
            else { component.withObject("/legacyExits").put(field.name(), exits); }
        }
        return true;
    }

    private static boolean readSpecial(JsonNode component, Field field, Hex hex, JsonNode legacy) {
        JsonNode value = component.get(field.name());
        boolean raw = legacy != null && legacy.has(field.name());
        int type = field.terrain();
        if (type == Terrains.DEPLOYMENT_ZONE && value.isArray()) {
            hex.addTerrain(new Terrain(type, 0, true, indices(value, 31, "deploymentZones")));
            return true;
        }
        if (type != Terrains.SOLARIS_ELEVATOR && type != Terrains.INDUSTRIAL_ELEVATOR) { return false; }
        require(value.isObject(), field.name() + " must be an elevator object");
        boolean solaris = type == Terrains.SOLARIS_ELEVATOR;
        int level = integer(value.get(solaris ? "alternateElevation" : "bottom"), field.name());
        int exits;
        if (raw) {
            exits = integer(legacy.get(field.name()), field.name() + ".legacyExits");
        } else if (solaris) { exits = indices(value.get("rolls"), 6, "solaris.rolls"); }
        else {
            int top = integer(value.get("top"), "industrial.top"), capacity = integer(value.get("capacity"), "industrial.capacity");
            require(top >= 0 && top <= 255 && capacity >= 0 && capacity <= 2550 && capacity % 10 == 0,
                  "Industrial elevator top must be 0..255; capacity must be 0..2550 tons in steps of 10");
            exits = IndustrialElevator.exits(top, capacity);
        }
        hex.addTerrain(new Terrain(type, level, true, exits));
        return true;
    }

    private static void indices(ObjectNode parent, String field, int mask, int count) {
        var values = parent.putArray(field);
        for (int i = 0; i < count; i++) { if ((mask & (1 << i)) != 0) { values.add(i + 1); } }
    }

    private static int indices(JsonNode values, int count, String path) {
        require(values != null && values.isArray(), path + " must be an integer array");
        int mask = 0;
        for (JsonNode value : values) {
            int index = integer(value, path);
            require(index >= 1 && index <= count, path + " values must be 1.." + count);
            mask |= 1 << (index - 1);
        }
        return mask;
    }

    private static int value(JsonNode value, int terrain, String path) {
        if (!value.isTextual()) { return integer(value, path); }
        int index = names(terrain).indexOf(value.textValue());
        require(index >= 0 && !value.textValue().isEmpty(), "Unknown value for " + path + ": " + value.textValue());
        return index;
    }

    static void putDirections(com.fasterxml.jackson.databind.node.ArrayNode list, int mask) {
        for (int direction = 0; direction < 6; direction++) {
            if ((mask & (1 << direction)) != 0) { list.add(DIRECTIONS.get(direction)); }
        }
    }

    static int directions(JsonNode list, String path) {
        require(list.isArray(), path + " must be an array of directions");
        int mask = 0;
        for (JsonNode item : list) {
            int direction = item.isTextual() ? DIRECTIONS.indexOf(item.textValue()) : -1;
            require(direction >= 0, "Unknown direction in " + path + ": " + item);
            mask |= 1 << direction;
        }
        return mask;
    }

    private static int integer(JsonNode value, String path) {
        require(value != null && value.isIntegralNumber() && value.canConvertToInt(), path + " must be an integer");
        return value.intValue();
    }

    private static ObjectNode object() { return JsonNodeFactory.instance.objectNode(); }
    private static void require(boolean condition, String message) { if (!condition) { throw new IllegalArgumentException(message); } }
}
