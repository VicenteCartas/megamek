/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.unitDisplay.UnitEquipmentActions;
import megamek.client.ui.panels.phaseDisplay.DeploymentDisplay;
import megamek.client.ui.panels.phaseDisplay.MovementDisplay;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.MiscType;
import megamek.common.equipment.Mounted;
import megamek.common.game.Game;
import megamek.common.units.DeploymentSetup;
import megamek.common.units.DeploymentSetup.Stance;
import megamek.common.units.Entity;
import megamek.common.units.Mek;

/**
 * The deploying unit's starting setup as the dock shows it (Core Rulebook p. 224): one tile per choice the unit has,
 * which choices differ from how it started out, and what the setup will cost it, such as heat each turn. Captured on
 * the Swing thread; a tile's choice maps back to a {@link DeploymentSetup} for {@link DeploymentDisplay#setSetup}.
 */
final class GpuDeploySetup {
    static final String STANCE = "stance";
    static final String POWER = "power";
    static final String SINKS = "sinks";
    /** The collapsed tag's length the changes in short fit in, and what joins them. */
    private static final int BRIEF = 30;
    private static final String MORE = " \u00B7 ";
    static final String EQUIPMENT = "eq:";

    /** How a tile chooses: a switch between two states, a list of states, or a count stepped up and down. */
    enum Kind { TOGGLE, PICK, STEP }

    /**
     * One choice: its caption, its states ({@code values}, or the counts 0..max for a step), the one chosen and the
     * one it started out with, for a toggle the state its switch shows as off, and why it can't change now ({@code
     * locked}, empty while it can).
     */
    record Tile(String id, String caption, List<String> values, int selected, int baseline, Kind kind, int off,
          String tip, String locked) {
        Tile {
            values = List.copyOf(values);
        }

        Tile(String id, String caption, List<String> values, int selected, int baseline, Kind kind, int off,
              String tip) {
            this(id, caption, values, selected, baseline, kind, off, tip, "");
        }

        boolean isLocked() {
            return !locked.isEmpty();
        }

        boolean changed() {
            return selected != baseline;
        }

        String value() {
            return values.get(selected);
        }

        /** Whether a toggle's switch shows on. */
        boolean on() {
            return selected != off;
        }

        /** The state at {@code index} as it reads: a count of the heat sinks' step out of all, else its name. */
        String shown(int index) {
            return kind == Kind.STEP ? text("sinksOf", index, values.size() - 1) : values.get(index);
        }

        /** Its state in short, for the collapsed tag: the stance or power alone, the heat sinks' count, else name and state. */
        String brief() {
            return switch (id) {
                case STANCE, POWER -> value();
                case SINKS -> text("sinksTag", selected, values.size() - 1);
                default -> caption + " " + value();
            };
        }
    }

    /**
     * The unit's setup tiles, how many differ from how it started out, what the setup costs it (amber lines), and a
     * short tag naming the change, or counting them, for the chip on the unit.
     */
    record Choice(int unitId, List<Tile> tiles, int changes, List<String> consequences, String tag) {
        static final Choice NONE = new Choice(Entity.NONE, List.of(), 0, List.of(), "");

        Choice {
            tiles = List.copyOf(tiles);
            consequences = List.copyOf(consequences);
        }

        @Nullable Tile tile(String id) {
            return tiles.stream().filter(tile -> tile.id().equals(id)).findFirst().orElse(null);
        }
    }

    private GpuDeploySetup() { }

    static Choice capture(Game game, DeploymentDisplay deployment) {
        return capture(game, deployment.currentEntity(), deployment.currentSetup(), deployment.setupOptions(),
              deployment.setupBaseline());
    }

    /** The setup tiles of a walk-on unit setting up its entry (walk-on step 1). */
    static Choice capture(Game game, MovementDisplay movement) {
        return capture(game, movement.currentEntity(), movement.currentSetup(), movement.setupOptions(),
              movement.setupBaseline());
    }

    static Choice capture(Game game, @Nullable Entity entity, @Nullable DeploymentSetup current,
          DeploymentSetup.Options options, @Nullable DeploymentSetup start) {
        if (entity == null || current == null || options.isEmpty()) {
            return Choice.NONE;
        }
        DeploymentSetup baseline = start == null ? current : start;
        List<Tile> tiles = new ArrayList<>();
        if (!options.stances().isEmpty()) {
            List<String> labels = options.stances().stream().map(stance -> stanceLabel(entity, stance)).toList();
            tiles.add(new Tile(STANCE, text("stance"), labels, Math.max(0, options.stances().indexOf(current.stance())),
                  Math.max(0, options.stances().indexOf(baseline.stance())), Kind.PICK, -1, text("stanceTip")));
        }
        if (options.sinks() > 0) {
            List<String> counts = IntStream.rangeClosed(0, options.sinks()).mapToObj(Integer::toString).toList();
            tiles.add(new Tile(SINKS, text("sinks"), counts, clamp(current.activeSinks(), options.sinks()),
                  clamp(baseline.activeSinks(), options.sinks()), Kind.STEP, 0, text("sinksTip")));
        }
        for (int equipmentNum : options.equipment()) {
            Tile tile = equipmentTile(game, entity, equipmentNum, current, baseline);
            if (tile != null) {
                tiles.add(tile);
            }
        }
        if (options.shutdown()) {
            tiles.add(new Tile(POWER, text("power"), List.of(text("running"), text("shutdown")),
                  current.shutdown() ? 1 : 0, baseline.shutdown() ? 1 : 0, Kind.TOGGLE, 1, text("powerTip")));
        }
        List<Tile> changed = tiles.stream().filter(Tile::changed).toList();
        String tag = brief(changed);
        return new Choice(entity.getId(), tiles, changed.size(), consequences(entity, tiles, options), tag);
    }

    /**
     * The setup {@code current} becomes when the tile {@code id} chooses its state {@code index}; null for a tile the
     * unit has none of.
     */
    static @Nullable DeploymentSetup choose(DeploymentSetup current, DeploymentSetup.Options options, String id,
          int index) {
        if (STANCE.equals(id)) {
            return index >= 0 && index < options.stances().size() ? current.withStance(options.stances().get(index))
                  : null;
        } else if (POWER.equals(id)) {
            return current.withShutdown(index == 1);
        } else if (SINKS.equals(id)) {
            return current.withActiveSinks(Math.max(0, Math.min(index, options.sinks())));
        } else if (id.startsWith(EQUIPMENT)) {
            int equipmentNum = Integer.parseInt(id.substring(EQUIPMENT.length()));
            return options.equipment().contains(equipmentNum) ? current.withMode(equipmentNum, index) : null;
        }
        return null;
    }

    private static @Nullable Tile equipmentTile(Game game, Entity entity, int equipmentNum, DeploymentSetup current,
          DeploymentSetup baseline) {
        Mounted<?> mounted = entity.getEquipment(equipmentNum);
        Integer selected = current.modes().get(equipmentNum);
        if (mounted == null || selected == null || selected < 0) {
            return null;
        }
        EquipmentType type = mounted.getType();
        int allowed = UnitEquipmentActions.modeChoices(game, entity, mounted).labels().size();
        int all = Math.min(type.getModesCount(mounted), mounted.getModesCount());
        int off = DeploymentSetup.modeIndex(mounted, Mounted.MODE_OFF);
        // An on/off pair stays a switch, locked while the server would refuse the other state (Stealth's ECM).
        boolean toggle = all == 2 && off >= 0 && off < all && selected < all;
        // Otherwise modes the server would refuse now are last, and left out; keep the current one listed all the same.
        int count = toggle ? all : Math.min(Math.max(allowed, selected + 1), mounted.getModesCount());
        List<String> values = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            values.add(mounted.getMode(index).getStateName(type));
        }
        String locked = toggle && 1 - selected >= allowed ? lockedReason(type) : "";
        int start = baseline.modes().getOrDefault(equipmentNum, selected);
        return new Tile(EQUIPMENT + equipmentNum, caption(entity, mounted), values, selected,
              start >= 0 && start < count ? start : selected, toggle ? Kind.TOGGLE : Kind.PICK, off, "", locked);
    }

    /** Why a switch can't change: stealth armor and its ECM suite hold each other. */
    private static String lockedReason(EquipmentType type) {
        if (type.hasFlag(MiscType.F_ECM)) {
            return text("ecmLocked");
        }
        return text(type.hasFlag(MiscType.F_STEALTH) ? "stealthLocked" : "locked");
    }

    /** The equipment's short name, with its location when the unit has more than one of it. */
    private static String caption(Entity entity, Mounted<?> mounted) {
        String name = mounted.getType().getShortName();
        long same = entity.getEquipment().stream()
              .filter(other -> other.getType() != null && other.getType().getShortName().equals(name)).count();
        return same > 1 ? name + " (" + entity.getLocationAbbr(mounted.getLocation()) + ")" : name;
    }

    private static String stanceLabel(Entity entity, Stance stance) {
        return stance == Stance.STANDING && !(entity instanceof Mek) ? text("stance.NORMAL")
              : text("stance." + stance.name());
    }

    /**
     * The changes in short for the collapsed tag: the first always, the next while they fit in {@link #BRIEF}
     * characters, then how many more ("MASC Off · ECM Suite Off", "Prone · +3").
     */
    static String brief(List<Tile> changed) {
        if (changed.isEmpty()) {
            return "";
        }
        StringBuilder brief = new StringBuilder(changed.getFirst().brief());
        int shown = 1;
        for (; shown < changed.size(); shown++) {
            String next = changed.get(shown).brief();
            // Leave room for the count of the rest, unless this is the last.
            int room = BRIEF - (shown == changed.size() - 1 ? 0 : MORE.length() + 2);
            if (brief.length() + MORE.length() + next.length() > room) {
                break;
            }
            brief.append(MORE).append(next);
        }
        if (shown < changed.size()) {
            brief.append(MORE).append('+').append(changed.size() - shown);
        }
        return brief.toString();
    }

    /** What the setup costs the unit: being shut down, heat sinks off, and equipment that builds heat while on. */
    private static List<String> consequences(Entity entity, List<Tile> tiles, DeploymentSetup.Options options) {
        List<String> lines = new ArrayList<>();
        for (Tile tile : tiles) {
            if (POWER.equals(tile.id()) && tile.selected() == 1) {
                lines.add(text("shutdownLine"));
            } else if (SINKS.equals(tile.id()) && tile.selected() < options.sinks()) {
                lines.add(text("sinksLine", options.sinks() - tile.selected()));
            } else if (tile.id().startsWith(EQUIPMENT)) {
                Mounted<?> mounted = entity.getEquipment(Integer.parseInt(tile.id().substring(EQUIPMENT.length())));
                int heat = mounted == null ? 0 : heatWhileOn(mounted, tile.selected());
                if (heat > 0) {
                    lines.add(text("heatLine", heat, tile.caption(), tile.value()));
                }
            }
        }
        return lines;
    }

    /** The heat each turn the equipment builds in its mode {@code index}, as the heat phase adds it. */
    private static int heatWhileOn(Mounted<?> mounted, int index) {
        if (!(mounted.getType() instanceof MiscType type) || index < 0 || index >= mounted.getModesCount()) {
            return 0;
        }
        String mode = mounted.getMode(index).getName();
        if (type.hasFlag(MiscType.F_PPC_CAPACITOR)) {
            return "Charge".equals(mode) ? 5 : 0;
        } else if (mode.equals(Mounted.MODE_OFF)) {
            return 0;
        } else if (type.hasFlag(MiscType.F_STEALTH) || type.hasFlag(MiscType.F_NULL_SIG)
              || type.hasFlag(MiscType.F_VOID_SIG)) {
            return 10;
        } else if (type.hasFlag(MiscType.F_CHAMELEON_SHIELD)) {
            return 6;
        } else if (type.hasFlag(MiscType.F_NOVA)) {
            return 2;
        }
        return 0;
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(value, max));
    }

    static String text(String key, Object... args) {
        return Messages.getString("GpuBoard.hud.deploy.setup." + key, args);
    }
}
