/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.client.ui.dialogs.GameMasterEditMemory;
import megamek.common.Hex;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.BuildingEditSpec;
import megamek.common.board.Coords;
import megamek.common.enums.BasementType;
import megamek.common.enums.BuildingType;
import megamek.common.equipment.FuelTank;
import megamek.common.units.IBuilding;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/**
 * A Game Master putting a building in a hex, changing the one there or taking it away, as the building card fills it
 * in. The form describes the structure that should stand in the hex when it is done; the server works out whether
 * that means putting one up, adjusting it or rebuilding it.
 *
 * <p>It handles one hex: multi-hex buildings joined by exits are not built or edited here, as in the Swing dialog it
 * replaces. The advanced buildings are units and belong with the tools that place units.</p>
 */
public final class BuildingEditForm {
    /** Comfortably above the sturdiest building in the rules. */
    public static final int MAX_CONSTRUCTION_FACTOR = 500;
    public static final int MAX_ARMOR = 200;
    public static final int MAX_HEIGHT = 20;
    public static final int MAX_MAGNITUDE = 100;
    /** How much less damage each ring further from an exploding tank takes. */
    private static final int MAGNITUDE_DEGRADATION = 10;
    /** Anything under one full ring explodes to no effect at all. */
    public static final int SMALLEST_USEFUL_MAGNITUDE = MAGNITUDE_DEGRADATION;
    private static final int DEFAULT_MAGNITUDE = 20;

    /** The classes a building may belong to, in the order they are offered. */
    private static final int[] CLASSES = { IBuilding.STANDARD, IBuilding.HANGAR, IBuilding.FORTRESS,
                                           IBuilding.GUN_EMPLACEMENT };
    private static final String[] CLASS_KEYS = { "BuildingEditDialog.class.standard",
                                                 "BuildingEditDialog.class.hangar",
                                                 "BuildingEditDialog.class.fortress",
                                                 "BuildingEditDialog.class.gunEmplacement" };

    /** How the card reaches the server and the tileset. */
    public interface Host {
        void sendBuildingEdit(BuildingEditSpec spec);

        /** @return the fluff levels the tileset defines for a hex holding {@code building}, ascending */
        List<Integer> fluffLevels(Hex building);
    }

    private final Coords coords;
    private final int boardId;
    private final @Nullable IBuilding existing;
    private final @Nullable Hex hex;
    private final Host host;
    private final List<BuildingType> types = new ArrayList<>();
    private final List<Integer> fluffLevels = new ArrayList<>();
    private boolean fuelTank;
    private BuildingType type;
    private int buildingClass;
    private BasementType basement = BasementType.values()[0];
    private int constructionFactor;
    private int constructionFactorMax = MAX_CONSTRUCTION_FACTOR;
    private int armor;
    private int height = 1;
    private int fluffImage;
    private int magnitude = DEFAULT_MAGNITUDE;

    /**
     * @param coords   the hex
     * @param boardId  the board it is on
     * @param existing the building standing in the hex, or null for none
     * @param hex      the hex as the board holds it, or null when it has none
     * @param host     how edits are sent and fluff images found
     */
    public BuildingEditForm(Coords coords, int boardId, @Nullable IBuilding existing, @Nullable Hex hex, Host host) {
        this.coords = coords;
        this.boardId = boardId;
        this.existing = existing;
        this.hex = hex;
        this.host = host;
        for (BuildingType each : BuildingType.values()) {
            // UNKNOWN is what the code uses for "no building here", not something to build
            if (each != BuildingType.UNKNOWN) {
                types.add(each);
            }
        }
        type = types.getFirst();
        load();
    }

    private void load() {
        if (existing == null) {
            type(BuildingType.MEDIUM);
            return;
        }
        if (existing instanceof FuelTank tank) {
            fuelTank = true;
            magnitude(tank.getMagnitude());
            constructionFactor(existing.getCurrentCF(coords));
            height(existing.getHeight(coords));
            refreshFluffLevels();
            return;
        }
        type(existing.getBuildingType());
        refreshBand(existing.getCurrentCF(coords));
        constructionFactor(existing.getCurrentCF(coords));
        armor(existing.getArmor(coords));
        height(existing.getHeight(coords));
        if (existing.getBasement(coords) != null) {
            basement = existing.getBasement(coords);
        }
        for (int index = 0; index < CLASSES.length; index++) {
            if (CLASSES[index] == existing.getBldgClass()) {
                buildingClass = index;
            }
        }
        refreshFluffLevels();
        if (hex != null) {
            fluffImage(BoardSceneryLayouts.buildingArt(hex));
        }
    }

    /** @return the card's title */
    public String title() {
        return Messages.getString("BuildingEditDialog.title", coords.getBoardNum());
    }

    /** @return whether a fuel tank is described rather than a building */
    public boolean fuelTank() {
        return fuelTank;
    }

    public void fuelTank(boolean value) {
        fuelTank = value;
    }

    /** @return the kinds of structure, building first */
    public static List<String> structureNames() {
        return List.of(Messages.getString("BuildingEditDialog.structure.building"),
              Messages.getString("BuildingEditDialog.structure.fuelTank"));
    }

    /** @return the building types offered */
    public List<BuildingType> types() {
        return List.copyOf(types);
    }

    public BuildingType type() {
        return type;
    }

    /** Chooses a type, which moves the construction factor to that type's own and holds it to the type's band. */
    public void type(BuildingType value) {
        type = value;
        refreshBand(value.getDefaultCF());
        constructionFactor(value.getDefaultCF());
        refreshFluffLevels();
    }

    /**
     * Holds the construction factor to the top of the type's band: a building stronger than its band is the next type
     * up. A factor already above it, as a board may say, stays allowed so it can be lowered.
     */
    private void refreshBand(int current) {
        constructionFactorMax = type.getMaximumCF() < 0 ? MAX_CONSTRUCTION_FACTOR
              : Math.max(type.getMaximumCF(), current);
    }

    /** @return the band of construction factors the type covers, or null for a type without one */
    public @Nullable String band() {
        return type.getMaximumCF() < 0 ? null : Messages.getString("BuildingEditDialog.constructionFactor.band",
              type.getMinimumCF(), type.getMaximumCF());
    }

    /** @return the class names, in order */
    public static List<String> classNames() {
        List<String> names = new ArrayList<>();
        for (String key : CLASS_KEYS) {
            names.add(Messages.getString(key));
        }
        return names;
    }

    /** @return the chosen class's index in {@link #classNames()} */
    public int buildingClass() {
        return buildingClass;
    }

    public void buildingClass(int index) {
        buildingClass = Math.max(0, Math.min(CLASSES.length - 1, index));
        refreshFluffLevels();
    }

    public BasementType basement() {
        return basement;
    }

    public void basement(BasementType value) {
        basement = value;
        refreshFluffLevels();
    }

    public int constructionFactor() {
        return constructionFactor;
    }

    public void constructionFactor(int value) {
        constructionFactor = Math.max(0, Math.min(constructionFactorMax, value));
    }

    public int constructionFactorMax() {
        return constructionFactorMax;
    }

    public int armor() {
        return armor;
    }

    public void armor(int value) {
        armor = Math.max(0, Math.min(MAX_ARMOR, value));
        refreshFluffLevels();
    }

    public int height() {
        return height;
    }

    public void height(int value) {
        height = Math.max(1, Math.min(MAX_HEIGHT, value));
        refreshFluffLevels();
    }

    public int magnitude() {
        return magnitude;
    }

    public void magnitude(int value) {
        magnitude = Math.max(SMALLEST_USEFUL_MAGNITUDE, Math.min(MAX_MAGNITUDE, value));
    }

    /** @return in plain terms what the magnitude does when the tank goes up, as HTML */
    public String magnitudeEffect() {
        int rings = magnitude / MAGNITUDE_DEGRADATION;
        if (rings < 1) {
            return Messages.getString("BuildingEditDialog.magnitudeEffect.none");
        }
        return Messages.getString("BuildingEditDialog.magnitudeEffect.text", magnitude, rings - 1,
              magnitude - (rings - 1) * MAGNITUDE_DEGRADATION);
    }

    /** @return the fluff images offered: zero for the type's ordinary artwork, then those the tileset defines */
    public List<Integer> fluffLevels() {
        return List.copyOf(fluffLevels);
    }

    /** @return the fluff image chosen, zero for the ordinary artwork */
    public int fluffImage() {
        return fluffImage;
    }

    /** Chooses a fluff image; one the building has no art for falls back to the ordinary artwork. */
    public void fluffImage(int level) {
        fluffImage = fluffLevels.contains(level) ? level : 0;
    }

    /** @return a fluff level's name */
    public static String fluffName(int level) {
        return level == 0 ? Messages.getString("BuildingEditDialog.fluffImage.default")
              : Messages.getString("BuildingEditDialog.fluffImage.numbered", level);
    }

    /** Works out again which images exist: the tileset ties them to the whole building, not only its type. */
    private void refreshFluffLevels() {
        fluffLevels.clear();
        fluffLevels.add(0);
        for (int level : host.fluffLevels(buildingAsHex(0))) {
            if (!fluffLevels.contains(level)) {
                fluffLevels.add(level);
            }
        }
        fluffImage(fluffImage);
    }

    /**
     * The described building written into a hex the way the board holds one, for the tileset, which answers questions
     * about hexes: the preview and the list of images both ask with it.
     *
     * @param fluff the fluff image to include, or zero for none
     */
    public Hex buildingAsHex(int fluff) {
        Hex building = new Hex();
        building.addTerrain(new Terrain(Terrains.BUILDING, type.getTypeValue()));
        building.addTerrain(new Terrain(Terrains.BLDG_ELEV, height));
        building.addTerrain(new Terrain(Terrains.BLDG_CF, constructionFactor));
        building.addTerrain(new Terrain(Terrains.BLDG_CLASS, CLASSES[buildingClass]));
        if (armor > 0) {
            building.addTerrain(new Terrain(Terrains.BLDG_ARMOR, armor));
        }
        building.addTerrain(new Terrain(Terrains.BLDG_BASEMENT_TYPE, basement.ordinal()));
        if (fluff > 0) {
            building.addTerrain(new Terrain(Terrains.BLDG_FLUFF, fluff));
        }
        return building;
    }

    /** @return the line saying what the hex held before a Game Master first changed it */
    public String original() {
        String before = GameMasterEditMemory.describeBeforeFirstEdit(coords);
        return before == null ? Messages.getString("BuildingEditDialog.original.unchanged")
              : Messages.getString("BuildingEditDialog.original.was", before);
    }

    /** @return whether the hex was changed and can be put back */
    public boolean mayRestore() {
        return GameMasterEditMemory.describeBeforeFirstEdit(coords) != null;
    }

    /** @return whether there is a building to take away */
    public boolean mayRemove() {
        return existing != null;
    }

    /** @return whether the hex is under water without a building, where none may be put up */
    public boolean inWater() {
        return hex != null && hex.depth() > 0 && !hex.containsTerrain(Terrains.BUILDING);
    }

    /**
     * Sends the described structure, remembering what the hex held first.
     *
     * @return false, sending nothing, when the hex is under water; the card says so before the trip to the server
     */
    public boolean apply() {
        if (inWater()) {
            return false;
        }
        GameMasterEditMemory.rememberBeforeFirstEdit(coords, describeHexNow());
        host.sendBuildingEdit(describedBuilding());
        return true;
    }

    /** Takes the building away, remembering what the hex held first. */
    public void remove() {
        BuildingEditSpec spec = new BuildingEditSpec(coords, boardId);
        spec.setRemovingBuilding(true);
        GameMasterEditMemory.rememberBeforeFirstEdit(coords, describeHexNow());
        host.sendBuildingEdit(spec);
    }

    /** Puts the hex back the way it was before any Game Master changed it. */
    public void restore() {
        BuildingEditSpec spec = new BuildingEditSpec(coords, boardId);
        spec.setRestoringOriginal(true);
        host.sendBuildingEdit(spec);
        GameMasterEditMemory.forget(coords);
    }

    BuildingEditSpec describedBuilding() {
        BuildingEditSpec spec = new BuildingEditSpec(coords, boardId);
        spec.setBuildingType(type);
        spec.setBuildingClass(CLASSES[buildingClass]);
        spec.setBasement(basement);
        spec.setConstructionFactor(constructionFactor);
        spec.setArmor(armor);
        spec.setHeight(height);
        spec.setFluffImage(fluffImage);
        spec.setFuelTank(fuelTank);
        spec.setMagnitude(magnitude);
        return spec;
    }

    private String describeHexNow() {
        if (existing == null) {
            return Messages.getString("BuildingEditDialog.original.nothing");
        }
        if (existing instanceof FuelTank tank) {
            return Messages.getString("BuildingEditDialog.original.fuelTank", existing.getCurrentCF(coords),
                  tank.getMagnitude());
        }
        return Messages.getString("BuildingEditDialog.original.building", existing.getBuildingType().toString(),
              existing.getCurrentCF(coords), existing.getHeight(coords));
    }
}
