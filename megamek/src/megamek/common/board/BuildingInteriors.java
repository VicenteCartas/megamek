/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.util.Map;

import megamek.common.Hex;
import megamek.common.enums.BasementType;
import megamek.common.enums.BuildingType;
import megamek.common.units.Entity;
import megamek.common.units.IBuilding;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/** Building access and usable floors. Exterior height and module assembly are independent of this choice. */
public enum BuildingInteriors {
    DEFAULT("default"), YES("yes"), NO_FLOORS("noFloors"), NO("no");

    private final String id;

    BuildingInteriors(String id) { this.id = id; }

    public String id() { return id; }

    public static BuildingInteriors choice(Hex hex) {
        int value = hex.terrainLevel(Terrains.BLDG_INTERIORS);
        return value > 0 && value < values().length ? values()[value] : DEFAULT;
    }

    public static BuildingInteriors resolve(Hex hex) {
        if (hex.terrainLevel(Terrains.BUILDING) == BuildingType.WALL.getTypeValue()) { return NO; }
        BuildingInteriors choice = choice(hex);
        return choice == DEFAULT ? defaultFor(hex.terrainLevel(Terrains.BLDG_CLASS), hex.getAppearance()) : choice;
    }

    public static BuildingInteriors defaultFor(int buildingClass, Map<String, HexAppearance> appearance) {
        HexAppearance design = appearance.get("building");
        return defaultFor(buildingClass, design == null ? null : design.variant(), design == null ? null : design.asset());
    }

    static BuildingInteriors defaultFor(int buildingClass, String variant, String asset) {
        if ("building/glass-dome".equals(variant) || "building/biodome".equals(variant)
              || "buildings/multihex/dome".equals(asset)
              || "buildings/multihex/biodome".equals(asset)) { return NO; }
        return buildingClass == IBuilding.HANGAR ? NO_FLOORS : YES;
    }

    public static void set(Hex hex, BuildingInteriors choice) {
        if (choice == DEFAULT) { hex.removeTerrain(Terrains.BLDG_INTERIORS); }
        else { hex.addTerrain(new Terrain(Terrains.BLDG_INTERIORS, choice.ordinal())); }
        normalizeBasement(hex);
    }

    /** Closed buildings have no basement gameplay, regardless of an authored basement value. */
    public static void normalizeBasement(Hex hex) {
        if (hex.containsTerrain(Terrains.BUILDING) && resolve(hex) == NO) {
            hex.addTerrain(new Terrain(Terrains.BLDG_BASEMENT_TYPE, BasementType.NONE.ordinal()));
            hex.removeTerrain(Terrains.BLDG_BASE_COLLAPSED);
        }
    }

    /** Aerospace altitude is not an interior floor; ordinary roof and above-roof access stays with the unit rules. */
    public static boolean allows(Entity entity, Hex hex, int elevation) {
        if (hex == null || !hex.containsTerrain(Terrains.BUILDING) || entity.isAero()
              || elevation >= hex.terrainLevel(Terrains.BLDG_ELEV)) { return true; }
        return switch (resolve(hex)) {
            case NO -> false;
            case NO_FLOORS -> elevation <= 0;
            default -> true;
        };
    }
}
