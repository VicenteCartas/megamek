/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.board.HexEditSpec;
import megamek.common.board.HexEditValidator;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/**
 * A Game Master repainting the ground hex by hex, as the terrain brush card holds it: set what the brush holds - a
 * terrain, its level, a ground level - then click hexes on the map to lay it down, as the map editor works. Every
 * painted hex is checked with the server's own rules while there is still something to change, and Apply sends the
 * edit and stays open to take it back.
 *
 * <p>The hex the brush was opened on is painted at once with what it already holds, and follows the brush until the
 * Game Master paints a hex themselves, so changing just that hex needs no click on the map. Structures are not painted
 * here: a building is built on the ground and taken down by the building card, through the collapse rules.</p>
 */
public final class HexBrush {
    /** The brush's first terrain choice: nothing, which strips a hex to bare ground. */
    public static final int BARE_GROUND = -1;

    /** The terrains a Game Master may lay down, in the order they are offered. */
    private static final int[] EDITABLE_TERRAINS = {
          Terrains.WATER, Terrains.RAPIDS, Terrains.WOODS, Terrains.JUNGLE, Terrains.ROUGH, Terrains.RUBBLE,
          Terrains.PAVEMENT, Terrains.ROAD, Terrains.SWAMP, Terrains.MUD, Terrains.SNOW, Terrains.ICE,
          Terrains.TUNDRA, Terrains.SAND, Terrains.MAGMA };

    /** The highest level worth offering for a terrain whose levels are open-ended, such as water depth. */
    private static final int HIGHEST_LEVEL_OFFERED = 10;
    public static final int LOWEST_GROUND = -30;
    public static final int HIGHEST_GROUND = 30;

    /** How the brush reaches the board and the server. */
    public interface Host {
        /** @return the hex at {@code coords} on the edited board, or null off it */
        @Nullable Hex hex(Coords coords);

        /** @return whether a unit stands in the hex, which the rules for water depend on */
        boolean occupied(Coords coords);

        void sendHexEdit(HexEditSpec spec);

        /** Marks the painted hexes on the board, replacing the previous marks. */
        void showMarks(Collection<Coords> painted);
    }

    private final int boardId;
    private final Host host;
    private final Map<Coords, HexEditSpec.HexPaint> painted = new LinkedHashMap<>();
    /** The hex the brush opened on while it still follows the brush, else null. */
    private Coords opened;
    private final Set<Coords> paintedOnOpening = new LinkedHashSet<>();
    private final List<Integer> levels = new ArrayList<>();
    private int terrain = BARE_GROUND;
    private int level = -1;
    private int factor;
    private int factorMax;
    private int bookFactor;
    private boolean changeGround;
    private int ground;
    private boolean sevenHexes;
    private boolean erasing;
    private boolean applied;

    /**
     * Opens the brush on a hex: set to what that hex holds, and that hex painted with it.
     *
     * @param boardId the board edited
     * @param coords  the hex the brush was opened on
     * @param host    how the brush reaches the board and the server
     */
    public HexBrush(int boardId, Coords coords, Host host) {
        this.boardId = boardId;
        this.host = host;
        loadFrom(coords);
        brushHex(coords);
        opened = coords;
        paintedOnOpening.addAll(footprintAround(coords));
    }

    /** Sets the brush to what a hex already holds, its factor included: woods shelled to 15 must not open at 50. */
    private void loadFrom(Coords coords) {
        Hex hex = host.hex(coords);
        if (hex == null) {
            return;
        }
        // a starting point only; the level is written to a hex only once the Game Master asks to change it
        ground = Math.max(LOWEST_GROUND, Math.min(HIGHEST_GROUND, hex.getLevel()));
        Terrain existing = brushableTerrainIn(hex);
        if (existing == null) {
            return;
        }
        terrain = existing.getType();
        rebuildLevels();
        int index = levels.indexOf(existing.getLevel());
        if (index >= 0) {
            level = index;
        }
        resetFactor();
        // a board may say what it likes, so a factor above the table is allowed; it may be lowered but not raised
        factorMax = Math.max(factorMax, existing.getTerrainFactor());
        factor = Math.max(0, Math.min(factorMax, existing.getTerrainFactor()));
    }

    /**
     * The terrain in a hex that the brush should start from: the first the brush can paint, in the order offered.
     *
     * @return the terrain, or null when the hex holds nothing the brush paints
     */
    public static @Nullable Terrain brushableTerrainIn(Hex hex) {
        for (int terrainType : EDITABLE_TERRAINS) {
            if (hex.containsTerrain(terrainType)) {
                return hex.getTerrain(terrainType);
            }
        }
        return null;
    }

    /** @return the terrain choices: bare ground, then each terrain named as the map editor names it */
    public static List<String> terrainNames() {
        List<String> names = new ArrayList<>();
        names.add(Messages.getString("HexEditDialog.bareGroundChoice"));
        for (int terrainType : EDITABLE_TERRAINS) {
            names.add(Terrains.getEditorName(terrainType));
        }
        return names;
    }

    /** @return the chosen terrain's index in {@link #terrainNames()} */
    public int terrainIndex() {
        if (terrain == BARE_GROUND) {
            return 0;
        }
        for (int index = 0; index < EDITABLE_TERRAINS.length; index++) {
            if (EDITABLE_TERRAINS[index] == terrain) {
                return index + 1;
            }
        }
        return 0;
    }

    /** Chooses a terrain by its index in {@link #terrainNames()}; its levels and book factor come with it. */
    public void terrainIndex(int index) {
        terrain = (index <= 0 || index > EDITABLE_TERRAINS.length) ? BARE_GROUND : EDITABLE_TERRAINS[index - 1];
        rebuildLevels();
        resetFactor();
        brushChanged();
    }

    private void rebuildLevels() {
        levels.clear();
        level = -1;
        if (terrain == BARE_GROUND) {
            return;
        }
        if (terrain == Terrains.WATER) {
            // depth 0 water is a legal hex, so water is the one terrain offered from zero
            levels.add(0);
        }
        levels.addAll(HexEditValidator.legalLevelsFor(terrain, HIGHEST_LEVEL_OFFERED));
        if (!levels.isEmpty()) {
            // water starts on its first real depth, which a Game Master reaching for water would see on the board
            level = (terrain == Terrains.WATER && levels.size() > 1) ? 1 : 0;
        }
    }

    /** @return the levels the terrain has, in the rules' words, the terrain's own name dropped from the front */
    public List<String> levelNames() {
        List<String> names = new ArrayList<>();
        for (int each : levels) {
            names.add(levelName(terrain, each));
        }
        return names;
    }

    static String levelName(int terrainType, int terrainLevel) {
        String fullName = Terrains.getDisplayName(terrainType, terrainLevel);
        if (fullName == null) {
            return Messages.getString("HexEditDialog.levelNumber", terrainLevel);
        }
        String shorter = fullName.replace(Terrains.getEditorName(terrainType), "").replace("()", "").trim();
        return shorter.isBlank() ? fullName : shorter;
    }

    /** @return the chosen level's index in {@link #levelNames()}, or -1 for bare ground */
    public int levelIndex() {
        return level;
    }

    /** Chooses a level, which puts the factor back to what the rules give that level when new. */
    public void levelIndex(int index) {
        if (index < 0 || index >= levels.size()) {
            return;
        }
        level = index;
        resetFactor();
        brushChanged();
    }

    private boolean hasTerrain() {
        return terrain != BARE_GROUND && level >= 0;
    }

    /** The rules give each terrain one factor and damage only takes it down, so the brush cannot go above it. */
    private void resetFactor() {
        bookFactor = hasTerrain() ? Terrains.getTerrainFactor(terrain, levels.get(level)) : 0;
        factorMax = Math.max(0, bookFactor);
        factor = factorMax;
    }

    /** @return whether the terrain has a factor to set */
    public boolean factorEnabled() {
        return hasTerrain() && bookFactor > 0;
    }

    public int factor() {
        return factor;
    }

    public int factorMax() {
        return factorMax;
    }

    public void factor(int value) {
        factor = Math.max(0, Math.min(factorMax, value));
        brushChanged();
    }

    /** @return what the rules give the terrain when new, "none" for one without a factor, or null for bare ground */
    public @Nullable String bookFactorText() {
        if (!hasTerrain()) {
            return null;
        }
        return bookFactor > 0 ? Messages.getString("HexEditDialog.terrainFactor.book", bookFactor)
              : Messages.getString("HexEditDialog.terrainFactor.none");
    }

    /** @return whether painting sets the ground level too, rather than following the ground */
    public boolean changeGround() {
        return changeGround;
    }

    public void changeGround(boolean value) {
        changeGround = value;
        brushChanged();
    }

    public int ground() {
        return ground;
    }

    public void ground(int value) {
        ground = Math.max(LOWEST_GROUND, Math.min(HIGHEST_GROUND, value));
        brushChanged();
    }

    /** @return the brush sizes: one hex, or the hex and the six around it */
    public static List<String> sizeNames() {
        return List.of(Messages.getString("HexEditDialog.brushSize.single"),
              Messages.getString("HexEditDialog.brushSize.seven"));
    }

    public boolean sevenHexes() {
        return sevenHexes;
    }

    public void sevenHexes(boolean value) {
        sevenHexes = value;
        brushChanged();
    }

    /** @return whether a click lifts hexes out of the edit rather than painting them */
    public boolean erasing() {
        return erasing;
    }

    public void erasing(boolean value) {
        erasing = value;
    }

    /**
     * @return what the brush holds, ready for a hex; the ground level only when asked for, so painting across a
     *       hillside follows the ground rather than flattening it
     */
    HexEditSpec.HexPaint brush() {
        HexEditSpec.HexPaint paint = new HexEditSpec.HexPaint();
        if (changeGround) {
            paint.setLevel(ground);
        }
        if (hasTerrain()) {
            paint.setTerrain(terrain, levels.get(level));
            paint.setTerrainFactor(terrain, factor);
        }
        return paint;
    }

    /**
     * Lays the brush on the hexes a click covers, or lifts them back out: erasing, or clicking the same stroke again,
     * takes it back; a changed brush paints over it.
     */
    public void brushHex(@Nullable Coords coords) {
        if (coords == null) {
            return;
        }
        forgetOpened();
        HexEditSpec.HexPaint paint = brush();
        List<Coords> footprint = footprintAround(coords);
        boolean sameStrokeAgain = true;
        for (Coords hex : footprint) {
            sameStrokeAgain &= paint.equals(painted.get(hex));
        }
        for (Coords hex : footprint) {
            if (erasing || sameStrokeAgain) {
                painted.remove(hex);
            } else {
                painted.put(hex, paint);
            }
        }
        changed();
    }

    /** @return the hexes one click covers, the ring of six too for the wide brush, none off the board */
    private List<Coords> footprintAround(Coords centre) {
        List<Coords> footprint = new ArrayList<>();
        for (Coords hex : centre.allAtDistanceOrLess(sevenHexes ? 1 : 0)) {
            if (host.hex(hex) != null) {
                footprint.add(hex);
            }
        }
        return footprint;
    }

    private void forgetOpened() {
        opened = null;
        paintedOnOpening.clear();
    }

    /**
     * Keeps the hex the brush opened on in step with it: a Game Master who opens on water and picks bare ground must
     * not send water. Hexes painted on purpose keep what they were given; Brush All gives them all the same.
     */
    private void brushChanged() {
        if (opened == null) {
            return;
        }
        for (Coords hex : paintedOnOpening) {
            painted.remove(hex);
        }
        paintedOnOpening.clear();
        HexEditSpec.HexPaint paint = brush();
        for (Coords hex : footprintAround(opened)) {
            painted.put(hex, paint);
            paintedOnOpening.add(hex);
        }
        changed();
    }

    /** Lays the brush on every hex painted so far, which is how a whole area is given one terrain. */
    public void brushAll() {
        forgetOpened();
        for (Coords hex : new ArrayList<>(painted.keySet())) {
            painted.put(hex, brush());
        }
        changed();
    }

    /** Lifts the most recently painted hex back out of the edit. */
    public void undoLastHex() {
        forgetOpened();
        if (!painted.isEmpty()) {
            painted.remove(new ArrayList<>(painted.keySet()).getLast());
            changed();
        }
    }

    public void clear() {
        forgetOpened();
        painted.clear();
        changed();
    }

    private void changed() {
        applied = false;
        host.showMarks(painted.keySet());
    }

    /** @return the hexes painted so far, in order */
    public List<Coords> painted() {
        return List.copyOf(painted.keySet());
    }

    /** @return each painted hex and what it will hold, in words, in the order painted */
    public List<String> paintedEntries() {
        List<String> entries = new ArrayList<>();
        for (Map.Entry<Coords, HexEditSpec.HexPaint> each : painted.entrySet()) {
            entries.add(Messages.getString("HexEditDialog.paintedEntry", each.getKey().getBoardNum(),
                  describe(each.getValue())));
        }
        return entries;
    }

    private static String describe(HexEditSpec.HexPaint paint) {
        if (paint.isBareGround()) {
            return Messages.getString("HexEditDialog.bareGroundChoice");
        }
        List<String> described = new ArrayList<>();
        for (Map.Entry<Integer, Integer> each : paint.getTerrainLevels().entrySet()) {
            described.add(Terrains.getDisplayName(each.getKey(), each.getValue()));
        }
        return String.join(", ", described);
    }

    /** @return where the ground and any water surface and bottom end up, so nobody does the arithmetic */
    public String groundText() {
        Integer depth = brush().getTerrainLevels().get(Terrains.WATER);
        boolean flooding = depth != null && depth > 0;
        if (!changeGround) {
            return flooding ? Messages.getString("HexEditDialog.groundKeptFlooded", depth)
                  : Messages.getString("HexEditDialog.groundKept");
        }
        return flooding ? Messages.getString("HexEditDialog.groundFlooded", ground, depth, ground - depth)
              : Messages.getString("HexEditDialog.groundDry", ground);
    }

    /** @return whether the edit may be applied: something is painted and every painted hex is legal */
    public boolean mayApply() {
        return !painted.isEmpty() && firstProblems().isEmpty();
    }

    /** @return whether the edit just applied may be taken back */
    public boolean mayUndo() {
        return applied;
    }

    /** @return whether the edit is legal, or the first painted hex's problems, or that it was applied */
    public String legality() {
        if (applied) {
            return Messages.getString("HexEditDialog.applied", painted.size());
        }
        if (painted.isEmpty()) {
            return Messages.getString("HexEditDialog.noHexes");
        }
        List<String> problems = firstProblems();
        return problems.isEmpty() ? Messages.getString("HexEditDialog.legal", painted.size())
              : Messages.getString("HexEditDialog.illegal", String.join(" ", problems));
    }

    /** @return what is wrong with the first painted hex that would break, the same check the server makes */
    private List<String> firstProblems() {
        for (Map.Entry<Coords, HexEditSpec.HexPaint> each : painted.entrySet()) {
            Coords coords = each.getKey();
            Hex hex = host.hex(coords);
            if (hex == null) {
                return List.of(Messages.getString("HexEditDialog.offBoard", coords.getBoardNum()));
            }
            List<String> problems = new ArrayList<>(HexEditValidator.problemsWithChange(hex,
                  editedCopyOf(hex, each.getValue()), host.occupied(coords)));
            if (!problems.isEmpty()) {
                problems.addFirst(Messages.getString("HexEditDialog.inHex", coords.getBoardNum()));
                return problems;
            }
        }
        return List.of();
    }

    /** @return the hex as it would be after the paint, any structure standing in it carried through */
    static Hex editedCopyOf(Hex hex, HexEditSpec.HexPaint paint) {
        Hex edited = hex.duplicate();
        if (paint.getLevel() != null) {
            edited.setLevel(paint.getLevel());
        }
        List<Terrain> structures = new ArrayList<>();
        for (int structureTerrain : HexEditValidator.carriedThroughTerrains()) {
            Terrain existing = edited.getTerrain(structureTerrain);
            if (existing != null) {
                structures.add(existing);
            }
        }
        edited.removeAllTerrains();
        for (Map.Entry<Integer, Integer> each : paint.getTerrainLevels().entrySet()) {
            edited.addTerrain(new Terrain(each.getKey(), each.getValue()));
        }
        for (Terrain structure : structures) {
            edited.addTerrain(structure);
        }
        return edited;
    }

    /** Sends the edit and stays open, offering to take it back; the board shows the change as players will see it. */
    public void apply() {
        if (!mayApply()) {
            return;
        }
        HexEditSpec spec = new HexEditSpec(boardId);
        for (Map.Entry<Coords, HexEditSpec.HexPaint> each : painted.entrySet()) {
            spec.paint(each.getKey(), each.getValue());
        }
        host.sendHexEdit(spec);
        applied = true;
    }

    /** Asks the server to put the hexes back the way they were before the edit just applied. */
    public void undo() {
        HexEditSpec undo = new HexEditSpec(boardId);
        undo.setUndoingLastEdit(true);
        host.sendHexEdit(undo);
        applied = false;
    }
}
