/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.Nullable;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.princess.PriorityTargetChoices;
import megamek.client.bot.princess.PriorityTargetChoices.Candidate;
import megamek.client.bot.princess.PriorityTargetChoices.ForceRole;
import megamek.client.ui.Messages;

/**
 * A bot's priority targets picked by name, without a toolkit, as the Swing {@code BotTargetPickerPanel} picks them:
 * the bot's enemies under their owner and force, each picked unit with a target priority from 1 (shoot first) to 5.
 * Owners start open and forces folded; while a search is typed every group with a match is open. Ticking a force ticks
 * the shown units in it at the default priority, and a force's priority sets every picked unit in it. Enemy convoy
 * units are always picked at priority 1 and cannot be unticked, so they are never part of {@link #picked}. Picked units
 * that are not listed (older settings, a team change) show under "Other targets", where they can be unticked.
 * <p>
 * The view shows {@link #rows} and acts on a row by its {@link Row#id}.
 */
public final class BotTargetPicker {
    /** What a row is. */
    public enum Kind {
        /** A player: a fold, no tick. */
        OWNER,
        /** A force: a fold and a tick for its units, and their priority once some are picked. */
        FORCE,
        /** The units in no force: a fold only. */
        NO_FORCE,
        /** A unit: a tick, and its priority once picked. */
        UNIT,
        /** The heading of the picked units that are not listed. */
        OTHER,
        /** The text shown when there are no rows. */
        EMPTY
    }

    /**
     * One row of the list.
     *
     * @param id              the row's id, which the actions take
     * @param depth           0 for an owner or heading, 1 for a force, 2 for a unit
     * @param open            whether a fold is open
     * @param checked         whether the tick is on
     * @param enabled         whether the tick may change
     * @param priority        the target priority shown, or 0 for none
     * @param priorityEnabled whether the priority may change
     * @param tip             the row's tooltip, or null
     */
    public record Row(Kind kind, String id, String text, int depth, boolean open, boolean checked, boolean enabled,
          int priority, boolean priorityEnabled, @Nullable String tip) { }

    private static final String OWNER = "owner-";
    private static final String FORCE = "force-";
    private static final String UNIT = "unit-";

    private final boolean game;
    private final List<Candidate> candidates;
    private final Map<Integer, String> unitNames;
    private final Set<Integer> listed = new HashSet<>();
    private final Map<Integer, Integer> picked = new HashMap<>();
    private final Set<Integer> closedOwners = new HashSet<>();
    private final Set<String> openForces = new HashSet<>();
    private String search = "";

    /**
     * @param game       whether there is a game; without one only the other targets are listed
     * @param candidates the bot's enemies' units, as {@link PriorityTargetChoices#targetsFor} sorts them
     * @param unitNames  every unit of the game by id, for the other targets
     * @param priorities the bot's priority targets so far, unit id to priority
     */
    public BotTargetPicker(boolean game, List<Candidate> candidates, Map<Integer, String> unitNames,
          Map<Integer, Integer> priorities) {
        this.game = game;
        this.candidates = List.copyOf(candidates);
        this.unitNames = Map.copyOf(unitNames);
        candidates.forEach(candidate -> listed.add(candidate.unitId()));
        picked(priorities);
    }

    /** The picked units, unit id to priority; enemy convoy units are left out, as the bot adds them itself. */
    public Map<Integer, Integer> picked() {
        return new HashMap<>(picked);
    }

    /** Replaces what is picked, as loading another configuration does. */
    public void picked(Map<Integer, Integer> priorities) {
        picked.clear();
        picked.putAll(priorities);
        candidates.stream().filter(Candidate::isAlwaysTarget).forEach(candidate -> picked.remove(candidate.unitId()));
    }

    public String search() {
        return search;
    }

    public void search(String text) {
        search = text == null ? "" : text;
    }

    private boolean searching() {
        return !search.isBlank();
    }

    /** The rows to show, from the top. */
    public List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        List<Candidate> shown = shown();
        int ownerId = Integer.MIN_VALUE;
        int index = 0;
        while (index < shown.size()) {
            Candidate first = shown.get(index);
            List<Candidate> force = run(shown, index);
            index += force.size();
            boolean ownerOpen = searching() || !closedOwners.contains(first.ownerId());
            if (first.ownerId() != ownerId) {
                ownerId = first.ownerId();
                rows.add(new Row(Kind.OWNER, OWNER + ownerId, Messages.getString("BotConfigDialog.pickerOwner",
                      first.ownerName(), first.team()), 0, ownerOpen, false, false, 0, false, null));
            }
            if (!ownerOpen) {
                continue;
            }
            boolean forceOpen = searching() || openForces.contains(forceKey(first));
            rows.add(forceRow(force, forceOpen));
            if (forceOpen) {
                force.forEach(unit -> rows.add(unitRow(unit)));
            }
        }
        List<Integer> others = otherTargets();
        if (!others.isEmpty()) {
            rows.add(new Row(Kind.OTHER, "other", Messages.getString("BotConfigDialog.pickerOtherTargets"), 0, true,
                  false, false, 0, false, Messages.getString("BotConfigDialog.pickerOtherTargetsTip")));
            for (int unitId : others) {
                String name = unitNames.get(unitId);
                String text = name == null ? Messages.getString("BotConfigDialog.unitListEntryNone", unitId)
                      : Messages.getString("BotConfigDialog.pickerUnit", name, unitId);
                rows.add(new Row(Kind.UNIT, UNIT + unitId, text, 1, true, true, true, picked.get(unitId), true, null));
            }
        }
        if (shown.isEmpty() && others.isEmpty()) {
            rows.add(new Row(Kind.EMPTY, "empty", Messages.getString(game ? "BotConfigDialog.pickerNoMatch"
                  : "BotConfigDialog.pickerNoGame"), 0, true, false, false, 0, false, null));
        }
        return rows;
    }

    private Row forceRow(List<Candidate> force, boolean open) {
        Candidate first = force.getFirst();
        String count = open ? "" : "  " + Messages.getString("BotConfigDialog.pickerTickedOfTotal",
              force.stream().filter(unit -> unit.isAlwaysTarget() || picked.containsKey(unit.unitId())).count(),
              force.size());
        String id = FORCE + forceKey(first);
        if (!first.hasForce()) {
            return new Row(Kind.NO_FORCE, id, Messages.getString("BotConfigDialog.pickerNoForce") + count, 1, open,
                  false, false, 0, false, null);
        }
        List<Candidate> pickable = pickable(force);
        List<Candidate> chosen = pickable.stream().filter(unit -> picked.containsKey(unit.unitId())).toList();
        boolean whole = pickable.isEmpty() || chosen.size() == pickable.size();
        int priority = chosen.isEmpty() ? 0 : picked.get(chosen.getFirst().unitId());
        return new Row(Kind.FORCE, id, first.forceName() + roleTag(first.forceRole()) + count, 1, open, whole,
              !pickable.isEmpty(), priority, priority > 0, Messages.getString("BotConfigDialog.pickerForceTip"));
    }

    private Row unitRow(Candidate unit) {
        String text = Messages.getString("BotConfigDialog.pickerUnit", unit.unitName(), unit.unitId());
        if (unit.isAlwaysTarget()) {
            return new Row(Kind.UNIT, UNIT + unit.unitId(), text, 2, true, true, false,
                  BehaviorSettings.HIGHEST_TARGET_PRIORITY, false, Messages.getString("BotConfigDialog.pickerConvoyTip"));
        }
        Integer priority = picked.get(unit.unitId());
        return new Row(Kind.UNIT, UNIT + unit.unitId(), text, 2, true, priority != null, true,
              priority == null ? 0 : priority, priority != null, null);
    }

    private static String roleTag(ForceRole role) {
        return switch (role) {
            case CONVOY -> "  [" + Messages.getString("BotConfigDialog.pickerConvoy") + "]";
            case ESCORT -> "  [" + Messages.getString("BotConfigDialog.pickerEscort") + "]";
            case NONE -> "";
        };
    }

    // ---- Actions by row id ----

    /** Opens or folds an owner or force row. */
    public void toggle(String id) {
        if (id.startsWith(OWNER)) {
            int owner = Integer.parseInt(id.substring(OWNER.length()));
            if (!closedOwners.remove(owner)) {
                closedOwners.add(owner);
            }
        } else if (id.startsWith(FORCE)) {
            String key = id.substring(FORCE.length());
            if (!openForces.remove(key)) {
                openForces.add(key);
            }
        }
    }

    /** Ticks or unticks a unit, or every shown pickable unit of a force. */
    public void tick(String id, boolean on) {
        if (id.startsWith(UNIT)) {
            int unitId = Integer.parseInt(id.substring(UNIT.length()));
            if (candidates.stream().anyMatch(unit -> unit.unitId() == unitId && unit.isAlwaysTarget())) {
                return;
            }
            if (on) {
                picked.put(unitId, BehaviorSettings.DEFAULT_TARGET_PRIORITY);
            } else {
                picked.remove(unitId);
            }
        } else if (id.startsWith(FORCE)) {
            for (Candidate unit : pickable(force(id))) {
                if (on) {
                    picked.putIfAbsent(unit.unitId(), BehaviorSettings.DEFAULT_TARGET_PRIORITY);
                } else {
                    picked.remove(unit.unitId());
                }
            }
        }
    }

    /** Sets the priority of a picked unit, or of every shown picked unit of a force. */
    public void priority(String id, int priority) {
        int value = Math.max(BehaviorSettings.HIGHEST_TARGET_PRIORITY,
              Math.min(BehaviorSettings.LOWEST_TARGET_PRIORITY, priority));
        if (id.startsWith(UNIT)) {
            int unitId = Integer.parseInt(id.substring(UNIT.length()));
            if (picked.containsKey(unitId)) {
                picked.put(unitId, value);
            }
        } else if (id.startsWith(FORCE)) {
            pickable(force(id)).stream().filter(unit -> picked.containsKey(unit.unitId()))
                  .forEach(unit -> picked.put(unit.unitId(), value));
        }
    }

    /** The count line under the list. */
    public String count() {
        long convoys = candidates.stream().filter(Candidate::isAlwaysTarget).count();
        if (convoys == 0) {
            return picked.isEmpty() ? Messages.getString("BotConfigDialog.pickerCountNone")
                  : Messages.getString("BotConfigDialog.pickerCount", picked.size());
        }
        return Messages.getString("BotConfigDialog.pickerCountConvoy", picked.size(), convoys);
    }

    // ---- Helpers ----

    private List<Candidate> shown() {
        return PriorityTargetChoices.matchingSearch(candidates, search);
    }

    /** The shown units of the force a row id names. */
    private List<Candidate> force(String id) {
        String key = id.substring(FORCE.length());
        return shown().stream().filter(unit -> forceKey(unit).equals(key)).toList();
    }

    private static List<Candidate> pickable(List<Candidate> force) {
        return force.stream().filter(unit -> !unit.isAlwaysTarget()).toList();
    }

    private static List<Candidate> run(List<Candidate> shown, int start) {
        Candidate first = shown.get(start);
        List<Candidate> run = new ArrayList<>();
        for (int index = start; index < shown.size(); index++) {
            Candidate candidate = shown.get(index);
            if (candidate.ownerId() != first.ownerId() || candidate.forceId() != first.forceId()) {
                break;
            }
            run.add(candidate);
        }
        return run;
    }

    private static String forceKey(Candidate unit) {
        return unit.ownerId() + ":" + unit.forceId();
    }

    private List<Integer> otherTargets() {
        return picked.keySet().stream().filter(unitId -> !listed.contains(unitId)).sorted().toList();
    }
}
