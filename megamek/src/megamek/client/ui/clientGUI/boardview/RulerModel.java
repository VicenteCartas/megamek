/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.equipment.MiscType;
import megamek.common.game.Game;
import megamek.common.units.Entity;
import megamek.common.units.EntityVisibilityUtils;
import megamek.common.units.Terrains;

/** EDT-owned native ruler input. Only immutable snapshots leave the client thread; the existing LOS helpers own rules. */
public final class RulerModel {
    public record Choice(int id, String name) {
        @Override public String toString() { return name; }
    }

    public record Endpoint(Coords coords, int entityId, String name, int height, int ground, int absoluteHeight,
          String heightLabel, String detail, boolean locked, List<Choice> choices) {
        public Endpoint { choices = List.copyOf(choices); }
    }

    public record Comparison(String mode, String forward, String reverse) { }

    public record Snapshot(boolean open, int pending, Endpoint start, Endpoint end, int distance, String mode,
          String forward, String reverse, String modifierSummary, boolean clear, boolean entityBased, BoardTactical.Ruler ruler,
          List<Comparison> comparison) {
        public static final Snapshot NONE = new Snapshot(false, 0, null, null, 0, "", "", "", "", true, false, null, List.of());
        public Snapshot { comparison = List.copyOf(comparison); }
    }

    /** Shared decision between actual-unit LOS and hypothetical endpoint heights, also used by the Swing ruler. */
    record Evaluation(LOSModifierCalculator.Measurement forward, String reverse, boolean entityBased) { }

    static Evaluation evaluate(Game game, int boardId, Player player, Coords from, Coords to, int h1, int h2,
          boolean mek1, boolean mek2, boolean alt1, boolean alt2, Entity unit1, Entity unit2,
          boolean actualHeights) {
        boolean entities = actualHeights && unit1 != null && unit2 != null
              && !sensor(player, unit1) && !sensor(player, unit2);
        if (entities) {
            return new Evaluation(LOSModifierCalculator.measureEntities(game, unit1, unit2, true),
                  LOSModifierCalculator.computeEntityBasedModifiers(game, unit2, unit1), true);
        }
        return new Evaluation(LOSModifierCalculator.measure(game, boardId, from, to, h1, h2, mek1, mek2,
                    alt1, alt2, player, true),
              LOSModifierCalculator.measure(game, boardId, to, from, h2, h1, mek2, mek1,
                    alt2, alt1, player, false).description(), false);
    }

    private static final class Point {
        final Coords coords;
        int entityId = Entity.NONE;
        Integer height;
        boolean identified;
        Point(Coords coords) { this.coords = coords; }
    }

    private final Game game;
    private final int boardId;
    private final Player player;
    private Point start;
    private Point end;
    private int locked;
    private boolean open;
    private boolean compare;

    public RulerModel(Game game, int boardId, Player player) {
        this.game = game;
        this.boardId = boardId;
        this.player = player;
    }

    public void open() { open = true; }
    public void clear() { start = null; end = null; locked = 0; open = false; compare = false; }
    public void compare(boolean value) { compare = value; }

    public void addPoint(Coords coords, Integer height) {
        if (coords == null || !game.getBoard(boardId).contains(coords)) { return; }
        open = true;
        if (locked == 1 && start != null) {
            if (!start.coords.equals(coords)) { end = point(coords, height); }
        } else if (locked == 2 && end != null) {
            if (!end.coords.equals(coords)) { start = point(coords, height); }
        } else if (start == null || end != null) {
            start = point(coords, height); end = null;
        } else if (start.coords.equals(coords)) {
            clear();
        } else {
            end = point(coords, height);
        }
    }

    public void measure(Coords from, Coords to, int entityId, Integer targetHeight) {
        clear();
        addPoint(from, null);
        if (entityId != Entity.NONE) { select(true, entityId); }
        addPoint(to, targetHeight);
    }

    public void height(boolean first, int value) {
        Point point = first ? start : end;
        if (point != null) { point.height = Math.max(-100, Math.min(200, value)); }
    }

    public void height(Coords coords, int value) {
        if (end != null && end.coords.equals(coords)) { height(false, value); }
        else if (start != null && start.coords.equals(coords)) { height(true, value); }
    }

    public void select(boolean first, int entityId) {
        Point point = first ? start : end;
        if (point == null) { return; }
        if (entityId == Entity.NONE || units(point.coords).stream().anyMatch(unit -> unit.getId() == entityId)) {
            point.entityId = entityId; point.height = null; point.identified = identified(point) != null;
        }
    }

    public void lock(boolean first, boolean value) { locked = value ? (first ? 1 : 2) : 0; }
    public void flip() {
        if (start == null || end == null) { return; }
        Point old = start; start = end; end = old;
        locked = locked == 0 ? 0 : 3 - locked;
    }

    private Point point(Coords coords, Integer height) {
        Point result = new Point(coords);
        units(coords).stream().max(Comparator.comparingInt(unit -> sensor(player, unit) ? 0
              : entityHeight(unit))).ifPresent(unit -> result.entityId = unit.getId());
        result.identified = identified(result) != null;
        result.height = height == null ? null : Math.max(-100, Math.min(200, height));
        return result;
    }

    private List<Entity> units(Coords coords) {
        return game.getEntitiesVector(coords, boardId).stream().filter(unit -> unit.isOnBoard(boardId)
              && (player == null || EntityVisibilityUtils.detectedOrHasVisual(player, game, unit))).toList();
    }

    private Entity entity(Point point) {
        return point == null ? null : units(point.coords).stream()
              .filter(unit -> unit.getId() == point.entityId).findFirst().orElse(null);
    }

    private static boolean sensor(Player player, Entity unit) {
        return player != null && EntityVisibilityUtils.onlyDetectedBySensors(player, unit);
    }

    private Entity identified(Point point) {
        Entity unit = entity(point);
        return unit == null || sensor(player, unit) ? null : unit;
    }

    private static boolean altitude(Entity unit) {
        return unit != null && unit.getAltitude() > 0 && DiagramUnitType.fromEntity(unit).isAltitudeUnit();
    }

    private static boolean mek(Entity unit) {
        return unit != null && DiagramUnitType.fromEntity(unit).isMek();
    }

    private static boolean elevation(Entity unit) {
        return unit != null && DiagramUnitType.fromEntity(unit).isElevationUnit();
    }

    /** Display physical ground-unit heights (standing Mek = 2); airborne elevation/altitude stays unchanged. */
    private static int entityHeight(Entity unit) {
        return altitude(unit) ? unit.getAltitude() : elevation(unit) ? unit.relHeight() : unit.relHeight() + 1;
    }

    /**
     * Convert the displayed endpoint only at the rules boundary. LosEffects uses ground-unit heights one lower
     * than their physical height. A bare hex at height zero keeps the engine's surface-hex convention; negative
     * manual elevations and airborne elevation/altitude are already engine levels. Known units retain their exact
     * relative height, including submerged units, when the other endpoint is measured manually.
     */
    private static int engineHeight(Endpoint endpoint, Entity unit) {
        if (altitude(unit)) { return endpoint.height(); }
        if (unit != null && endpoint.height() == entityHeight(unit)) { return unit.relHeight(); }
        if (elevation(unit) || endpoint.height() <= 0) { return endpoint.height(); }
        return LOSHeightCalculation.toAbsoluteHeight(endpoint.height(), 0);
    }

    private Endpoint endpoint(Point point, boolean first) {
        if (point == null) { return null; }
        Hex hex = game.getBoard(boardId).getHex(point.coords);
        Entity unit = entity(point);
        boolean known = unit != null && !sensor(player, unit);
        // Forget hidden or departed units, including a manual override that could reveal their previous height.
        if (unit == null && point.entityId != Entity.NONE) { point.entityId = Entity.NONE; point.height = null; }
        if (point.identified && !known) { point.height = null; }
        point.identified = known;
        int height = point.height != null ? point.height : known ? entityHeight(unit) : 0;
        boolean alt = known && altitude(unit);
        String label = alt ? "Altitude" : known && elevation(unit) ? "Elevation" : "Height";
        String terrain = terrainName(hex);
        String name = unit == null ? terrain : known ? unit.getDisplayName() : Messages.getString("BoardView1.sensorReturn");
        List<String> details = new ArrayList<>();
        details.add(terrain);
        if (known) {
            if (unit.isProne()) { details.add("Prone"); }
            if (unit.isHullDown()) { details.add("Hull down"); }
            if (unit.hasWorkingMisc(MiscType.F_MAST_MOUNT)) { details.add("Mast mount (spotting only)"); }
        }
        List<Choice> choices = new ArrayList<>();
        choices.add(new Choice(Entity.NONE, "Terrain / manual"));
        for (Entity candidate : units(point.coords)) {
            choices.add(new Choice(candidate.getId(), sensor(player, candidate)
                  ? Messages.getString("BoardView1.sensorReturn") : candidate.getDisplayName()));
        }
        return new Endpoint(point.coords, unit == null ? Entity.NONE : unit.getId(), name, height, hex.getLevel(),
              alt ? height : hex.getLevel() + height, label,
              String.join(" · ", details), locked == (first ? 1 : 2), choices);
    }

    private static String terrainName(Hex hex) {
        List<String> names = new ArrayList<>();
        for (int type : hex.getTerrainTypes()) {
            String name = Terrains.getDisplayName(type, hex.terrainLevel(type));
            if (name != null && !name.isBlank()) { names.add(name); }
        }
        return names.isEmpty() ? Messages.getString("Ruler.terrainClear") : String.join(", ", names);
    }

    public Snapshot capture() {
        if (!open) { return Snapshot.NONE; }
        Board board = game.getBoard(boardId);
        if (board == null || start != null && !board.contains(start.coords) || end != null && !board.contains(end.coords)) {
            clear(); return Snapshot.NONE;
        }
        Endpoint a = endpoint(start, true), b = endpoint(end, false);
        String mode = switch (LosRuleMode.fromGameOptions(game)) {
            case STANDARD -> "Standard LOS";
            case DIAGRAMMED -> "Diagrammed LOS";
            case DEAD_ZONE -> "Dead zone LOS";
        };
        if (a == null || b == null) {
            return new Snapshot(true, InputEvent.ALT_DOWN_MASK, a, b, 0, mode, "", "", "", true, false,
                  a == null ? null : new BoardTactical.Ruler(a.coords(), null, a.absoluteHeight(), 0, null, null,
                        GUIPreferences.getInstance().getRulerColor1().getRGB(), GUIPreferences.getInstance().getRulerColor2().getRGB()), List.of());
        }
        Entity first = identified(start), second = identified(end);
        boolean actualHeights = first != null && second != null && a.height() == entityHeight(first)
              && b.height() == entityHeight(second);
        var forward = measure(a, b, first, second, actualHeights, true);
        var reverse = measure(b, a, second, first, actualHeights, false);
        var effects = forward.effects();
        var ruler = new BoardTactical.Ruler(a.coords(), b.coords(), a.absoluteHeight(), b.absoluteHeight(),
              effects.getBlockingHex(), effects.getModifierHex(), GUIPreferences.getInstance().getRulerColor1().getRGB(),
              GUIPreferences.getInstance().getRulerColor2().getRGB());
        List<Comparison> comparisons = List.of();
        if (compare) {
            var values = LOSModifierCalculator.compare(game,
                  () -> measure(a, b, first, second, actualHeights, false).description(),
                  () -> measure(b, a, second, first, actualHeights, false).description());
            comparisons = List.of(new Comparison("Standard", values.standardAttacker(), values.standardTarget()),
                  new Comparison("Diagrammed", values.diagrammedAttacker(), values.diagrammedTarget()),
                  new Comparison("Dead zone", values.deadZoneAttacker(), values.deadZoneTarget()));
        }
        return new Snapshot(true, 0, a, b, a.coords().distance(b.coords()), mode, forward.description(),
              reverse.description(), forward.summary(), effects.canSee(), actualHeights, ruler, comparisons);
    }

    private LOSModifierCalculator.Measurement measure(Endpoint from, Endpoint to, Entity first, Entity second,
          boolean actualHeights, boolean trace) {
        return actualHeights ? LOSModifierCalculator.measureEntities(game, first, second, trace)
              : LOSModifierCalculator.measureAtHeights(game, boardId, from.coords(), to.coords(),
                    engineHeight(from, first), engineHeight(to, second),
                    mek(first), mek(second), altitude(first), altitude(second), player, trace);
    }
}
