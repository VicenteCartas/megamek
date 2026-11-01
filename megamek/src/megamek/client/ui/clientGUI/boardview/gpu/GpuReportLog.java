/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import megamek.client.ui.dialogs.RoundsInAirDialog;
import megamek.common.Report;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.WeaponType;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;

/** EDT-owned formatting cache of the client's already visibility-filtered reports. No live entities cross to GL. */
final class GpuReportLog {
    private static final Pattern UNIT_LINK = Pattern.compile(
          "(?is)<a\\b[^>]*href\\s*=\\s*['\"]#entity:(\\d+)['\"][^>]*>(.*?)</a>");
    private static final Pattern LINK = Pattern.compile(
          "(?is)<a\\b[^>]*href\\s*=\\s*(['\"])(#entity:\\d+|#tooltip:.*?)\\1\\s*>(.*?)</a>");
    /** MegaMek's own emphasis for grave results (critical hits, destroyed locations, explosions). */
    private static final Pattern WARNING = Pattern.compile("(?i)class\\s*=\\s*['\"]warning['\"]");
    private static final Set<Integer> ACTOR_HEADERS = Set.of(3100, 3101, 3102, 4005, 5630);
    /**
     * The phase each phase marker starts. The heat phase (5000) and the control rolls (5001) are sections of the end
     * phase that carry their own heading.
     */
    private static final Map<Integer, GamePhase> PHASES = Map.ofEntries(Map.entry(1000, GamePhase.INITIATIVE),
          Map.entry(1005, GamePhase.INITIATIVE), Map.entry(1010, GamePhase.INITIATIVE),
          Map.entry(1035, GamePhase.TARGETING), Map.entry(1100, GamePhase.OFFBOARD),
          Map.entry(2000, GamePhase.MOVEMENT), Map.entry(3000, GamePhase.FIRING), Map.entry(4000, GamePhase.PHYSICAL),
          Map.entry(5000, GamePhase.END), Map.entry(5001, GamePhase.END), Map.entry(5005, GamePhase.END),
          Map.entry(7000, GamePhase.VICTORY));
    private static final Set<Integer> SECTIONS = Set.of(5000, 5001);
    /**
     * The initiative markers that open a combat round, "Initiative Phase for Round #n" and "... for Deployment for
     * Round #n" (TWGameManager.writeInitiativeReport); 1005 opens the start-of-game deployment instead.
     */
    private static final Set<Integer> COMBAT_INITIATIVES = Set.of(1000, 1010);
    /** PSR subject lines that end with the reasons for the roll. */
    private static final Set<Integer> PSR_REASONS = Set.of(2180, 2195, 2200, 2275, 2280);
    /** Lines that name the unit making the following PSR: the reason lines, breaking free (2340), thrashing (4140). */
    private static final Set<Integer> PSR_SUBJECTS = Stream.concat(PSR_REASONS.stream(), Stream.of(2340, 4140))
          .collect(Collectors.toUnmodifiableSet());
    /**
     * The one classification table (K2). A phase marker sets the kind of the entries that follow it; an attack start
     * or a PSR subject line sets the kind of the entry it is in. Other ids: OTHER. PSRs also run inside the heat phase
     * (HeatResolver), so the "Piloting Rolls" header sets no kind.
     */
    private static final Map<Integer, Kind> KINDS = kinds();
    private static final Set<Integer> ATTACK_STARTS = KINDS.entrySet().stream()
          .filter(kind -> kind.getValue() == Kind.WEAPON || kind.getValue() == Kind.PHYSICAL).map(Map.Entry::getKey)
          .collect(Collectors.toUnmodifiableSet());
    /**
     * PSR roll lines and the data index of their target number; the roll and the result are the last two data. 2300 is
     * the engine stall or unstall roll of an ICE-engine Mek (Mek.doCheckEngineStallRoll, checkUnstall).
     */
    private static final Map<Integer, Integer> PSR_ROLLS = Map.of(2185, 0, 2190, 0, 2193, 1, 2299, 0, 2300, 0, 2306,
          0);
    /** PSR lines that fail without a roll. */
    private static final Set<Integer> PSR_AUTOMATIC_FAILURES = Set.of(2275, 2296);
    /**
     * Inferno explosion, start-up and shutdown checks, and the ammunition explosion check at heat 19 and more (5065,
     * a heat check by lead decision); the result is the last data.
     */
    private static final Set<Integer> HEAT_CHECKS = Set.of(5040, 5050, 5060, 5065);
    /**
     * Lines HeatResolver writes only when a running unit's heat reaches the shutdown range: automatic shutdown,
     * shutdown without an active pilot, shutdown avoided by a Triple-Core Processor, and the shutdown check.
     */
    private static final Set<Integer> SHUTDOWN_RANGE = Set.of(5055, 5056, 5057, 5060);
    /** "... gains N heat, sinks M heat and is now at H heat": N, M and H are data 2, 3 and 4. */
    private static final int HEAT_REPORT = 5035;
    private static final int HEAT_REPORT_GAINED = 2;
    private static final int HEAT_REPORT_SUNK = 3;
    private static final int HEAT_REPORT_HEAT = 4;
    /**
     * Weapon (3150, 3155) and physical (4025) to-hit lines and the heat checks: the data index of the target number and
     * of the roll.
     */
    private static final Map<Integer, Integer> TARGET_NUMBERS = Map.of(3150, 0, 4025, 0, 5040, 2, 5050, 2, 5060, 2,
          5065, 2);
    private static final Map<Integer, Integer> ROLLS = Map.of(3155, 0, 4025, 1, 5040, 3, 5050, 3, 5060, 3, 5065, 3);
    /** Attack starts whose target may be a hex or a building; they name any target as plain text. */
    private static final Set<Integer> PLACE_TARGETS = Set.of(3117, 3120, 3126);
    /** "..., but the shot (punch, kick, ...) is impossible (reason)": the reason is data 0. */
    private static final Set<Integer> IMPOSSIBLE = Set.of(3135, 4015, 4060, 4075, 4090, 4115, 4147, 4160, 4220, 4222,
          4255, 4285, 4300, 4310, 4551, 4561, 4581);
    /** " SECTION DESTROYED." */
    private static final int LOCATION_DESTROYED = 6115;
    /** "*** unit DESTROYED by reason! ***" */
    private static final int UNIT_DESTROYED = 6365;
    /** "weapon (ammo ammo) at target": the attack start of a non-standard ammunition, whose name is data 1. */
    private static final int AMMO_ATTACK = 3116;
    /**
     * "Critical hit on location." before the roll (6310) of a critical hit check (TWGameManager.criticalEntity,
     * criticalTank); without the roll, the weapon a vehicle's critical hit destroyed (applyTankCritical).
     */
    private static final int CRITICAL_CHECK = 6305;
    private static final int CRITICAL_ROLL = 6310;
    /** "Location is empty, so criticals transfer to location." */
    private static final int CRITICAL_TRANSFER = 6335;
    /** "CRITICAL HIT on slot.": a Mek's or ProtoMek's system, equipment, or a battle armor trooper. */
    private static final int CRITICAL_SLOT = 6225;
    /**
     * The critical hit results of vehicles (TWGameManager.applyTankCritical) and of aerospace units and large craft
     * (applyAeroCritical, applyCargoCritical) that MegaMek reports in a line of their own; ids it also writes for other
     * causes (motive damage, landing, explosions) are left out.
     */
    private static final Set<Integer> CRITICAL_EFFECTS = Set.of(6185, 6190, 6210, 6215, 6600, 6605, 6607, 6610, 6615,
          6620, 6625, 6630, 6635, 6640, 6645, 6650, 6655, 6665, 9105, 9110, 9130, 9135, 9140, 9150, 9152, 9160, 9165,
          9166, 9170, 9175, 9176, 9180, 9185, 9186, 9187, 9188, 9189, 9190, 9191, 9194, 9195, 9196, 9197);

    record Unit(int id, String name) {
        @Override
        public String toString() { return id < 0 ? name : name + "  [" + id + "]"; }
    }

    record Link(int start, int end, int unitId, String detail) { }
    record Text(String text, List<Link> links) { }

    /** What an entry reports, from its report ids (K2, K6). */
    enum Kind { WEAPON, PHYSICAL, PSR, HEAT, MOVE, INITIATIVE, OTHER }

    /**
     * One log entry. {@code phase} names its report section (a phase, or the heat or control-roll section of the end
     * phase) and {@code gamePhase} is that section's phase. {@code attackerId} and {@code targetId} are set for weapon
     * and physical entries (the target is {@link Entity#NONE} for a hex or a building); {@code attack} is the linked
     * combat event (null if none) and {@code psr} the ids of the {@link PsrItem}s the entry reports. {@code critical}:
     * a line MegaMek marks as a warning, a failed PSR, or a heat alert (K3). {@code heatAlert}: a failed heat check or
     * a shutdown-range heat line, the mock's failed check or heat of 14 and more (K5). Values of the entry's report
     * lines, null when it has none or the value is hidden or not a plain number: the {@code targetNumber} and
     * {@code roll} of the attack or of the first heat check, {@code ammo} the non-standard ammunition the attack names,
     * {@code heat} the resulting heat, {@code heatGained} and {@code heatSunk} the heat gained and sunk this turn.
     * {@code notFired}: the reason MegaMek reports an attack as impossible (empty when hidden), null for an attack
     * that was made. {@code locationDestroyed}: a "SECTION DESTROYED" line; {@code unitDestroyed}: a "*** ...
     * DESTROYED by ... ***" line.
     */
    record Entry(int round, String phase, GamePhase gamePhase, String heading, String text, List<Unit> units,
          String rolls, List<Link> links, Kind kind, int attackerId, int targetId, UUID attack, List<Long> psr,
          boolean critical, boolean heatAlert, Integer targetNumber, Integer roll, String notFired, String ammo,
          Integer heat, Integer heatGained, Integer heatSunk, boolean locationDestroyed, boolean unitDestroyed) {
        Entry {
            psr = List.copyOf(psr);
        }

        boolean matches(int turn, String phaseFilter, int unitId, String query) {
            return (turn == 0 || round == turn) && (phaseFilter.isEmpty() || phase.equals(phaseFilter))
                  && (unitId < 0 || units.stream().anyMatch(unit -> unit.id() == unitId))
                  && (query.isBlank() || (heading + " " + text + " " + rolls).toLowerCase(Locale.ROOT)
                        .contains(query.toLowerCase(Locale.ROOT).strip()));
        }

        /** The "My force" filter (K3): the entry names one of {@code unitIds} as attacker, target or subject. */
        boolean involves(Set<Integer> unitIds) {
            return units.stream().anyMatch(unit -> unitIds.contains(unit.id()));
        }

        private Entry linked(UUID event) {
            return new Entry(round, phase, gamePhase, heading, text, units, rolls, links, kind, attackerId, targetId,
                  event, psr, critical, heatAlert, targetNumber, roll, notFired, ammo, heat, heatGained, heatSunk,
                  locationDestroyed, unitDestroyed);
        }
    }

    /**
     * One weapon or physical attack the client showed, for playback review. Destructions and the counter shots of
     * AMS and point defense are not recorded, so {@code hit} counts attack hits only. {@code phase} is the client's
     * phase when it handled the event: the server sends a phase's attacks before it changes to the phase's report, and
     * the client handles packets in order on the EDT, so it is the phase whose report section holds the attack.
     */
    record CombatEvent(UUID id, int round, GamePhase phase, ResolvedAttack.Kind kind, int attackerId, int targetId,
          String weapon, String weaponLocation, boolean hit, int damage, List<ResolvedAttack.Impact> impacts,
          Integer missileHits, int missiles) {
        CombatEvent {
            impacts = List.copyOf(impacts);
        }
    }

    /**
     * One piloting skill roll of a disclosed unit, from its PSR report lines. {@code id} is stable: the round and the
     * index of the roll's report. A fall without a roll has {@link TargetRoll#AUTOMATIC_FAIL} and roll 0.
     */
    record PsrItem(long id, int round, GamePhase phase, int entityId, int targetNumber, int roll, boolean passed,
          String reasons) { }

    /**
     * One critical hit on a disclosed unit, from its report lines: the location of the critical hit check ("" when the
     * report names none, as for aerospace units) and what it hit, the slot's name or MegaMek's line for a vehicle's or
     * aerospace unit's result. {@code id} is stable as a {@link PsrItem}'s.
     */
    record CritItem(long id, int round, GamePhase phase, int entityId, String location, String text) { }

    /** One completed move the client showed. */
    record MoveEvent(long sequence, int round, int entityId, String type, int mpUsed, int hexes, Coords from, Coords to,
          int facing) { }

    /**
     * The log. {@code round} is the round the player sees ({@link GpuReportLog#displayRound(int, List)}), which the
     * entries and events carry too, at least 1. {@code combat}, {@code psr}, {@code moves} and {@code crits} belong to
     * the current round; {@code inFlight} holds the artillery rounds in the air, the rows of the Rounds in the Air
     * window (K13).
     */
    record Snapshot(int round, GamePhase phase, List<Entry> entries, Map<Integer, BoardScene.Pixels> icons,
          List<CombatEvent> combat, List<PsrItem> psr, List<MoveEvent> moves,
          List<RoundsInAirDialog.Row> inFlight, List<CritItem> crits) {
        static final Snapshot EMPTY = new Snapshot(0, GamePhase.UNKNOWN, List.of(), Map.of(), List.of(), List.of(),
              List.of(), List.of());

        Snapshot {
            entries = List.copyOf(entries);
            icons = Map.copyOf(icons);
            combat = List.copyOf(combat);
            psr = List.copyOf(psr);
            moves = List.copyOf(moves);
            inFlight = List.copyOf(inFlight);
            crits = List.copyOf(crits);
        }

        /** A log without critical hits. */
        Snapshot(int round, GamePhase phase, List<Entry> entries, Map<Integer, BoardScene.Pixels> icons,
              List<CombatEvent> combat, List<PsrItem> psr, List<MoveEvent> moves,
              List<RoundsInAirDialog.Row> inFlight) {
            this(round, phase, entries, icons, combat, psr, moves, inFlight, List.of());
        }

        /**
         * This round's attacks of a disclosed unit that MegaMek reports as impossible, in report order. MegaMek sends
         * no event for them, so review shows only their card, "Not fired" (J10).
         */
        List<Entry> notFired() {
            int logRound = Math.max(1, round);
            return entries.stream().filter(entry -> entry.round() == logRound && entry.notFired() != null
                  && entry.attackerId() != Entity.NONE).toList();
        }

        /**
         * The steps review goes through (J4, J10, K12): this round's weapon and physical attacks, its attacks that were
         * not fired and its PSR items.
         */
        int reviewable() {
            return combat.size() + notFired().size() + psr.size();
        }
    }

    /** One round's reports, formatted: the entries (unlinked) and the PSR items they report. */
    record Round(List<Report> source, int size, List<Entry> entries, List<PsrItem> psr, List<CritItem> crits) { }

    private record LinkKey(GamePhase phase, int attackerId, int targetId, boolean weapon) { }

    private List<Round> rounds = List.of();
    private Snapshot snapshot = Snapshot.EMPTY;
    private String liveHtml = "";
    private int liveRound;
    private GamePhase livePhase = GamePhase.UNKNOWN;
    private boolean liveChanged;
    private final List<CombatEvent> combat = new ArrayList<>();
    private final List<MoveEvent> moves = new ArrayList<>();
    private int eventRound;
    private long moveSequence;
    private boolean eventsChanged;

    /**
     * EDT: an attack the client animates, after the source's visibility checks. Kept until the round changes. A
     * destruction is skipped (its report line sets {@link Entry#unitDestroyed}), and so is a defensive shot: the
     * counter of an AMS or of a point defense bay's member weapon is part of the attack it counters, whose report entry
     * holds its lines and whose volley plays it. Only an AMS used as a weapon (TacOps manual AMS) makes its own attack.
     */
    void combat(ResolvedAttack result, Entity attacker, Targetable target, int round, GamePhase phase) {
        if (result.kind() == ResolvedAttack.Kind.DEATH) {
            return;
        }
        ResolvedAttack.Shot shot = result.shot();
        Mounted<?> equipment = attacker.getEquipment(result.equipmentIndex());
        boolean aimed = equipment instanceof WeaponMounted weapon && weapon.getType().hasFlag(WeaponType.F_AMS)
              && !weapon.firesAutomatically();
        if (shot != null && shot.defensive() && !aimed) {
            return;
        }
        int logRound = keepRound(Math.max(1, displayRound(round)));
        // WeaponHandler names the attack by its weapon type, so the event keeps the same name for linking.
        combat.add(new CombatEvent(result.id(), logRound, phase, result.kind(), attacker.getId(),
              target instanceof Entity entity ? entity.getId() : Entity.NONE,
              equipment == null ? "" : equipment.getType().getName(),
              result.limb() >= 0 ? attacker.getLocationAbbr(result.limb()) : "", result.hit(),
              result.impacts().stream().mapToInt(ResolvedAttack.Impact::weight).sum(), result.impacts(),
              shot == null ? null : shot.missileHits(), shot == null ? 0 : shot.missiles()));
        eventsChanged = true;
    }

    /**
     * EDT: a move the client animates, after the source's visibility checks. {@code start} is where the unit stood
     * before the move (null if unknown) and {@code entity} is already at its end. Kept until the round changes.
     */
    void moved(Entity entity, UnitLocation start, List<UnitLocation> path, EntityMovementType type, int round) {
        if (path.isEmpty()) {
            return;
        }
        int logRound = keepRound(Math.max(1, displayRound(round)));
        UnitLocation end = path.getLast();
        moves.add(new MoveEvent(++moveSequence, logRound, entity.getId(), entity.getMovementString(type),
              entity.mpUsed, entity.delta_distance, start == null ? null : start.coords(), end.coords(), end.facing()));
        eventsChanged = true;
    }

    /** Keeps the combat and move events of one log round only (0: none); a new round starts empty. */
    private int keepRound(int logRound) {
        if (logRound != eventRound) {
            eventRound = logRound;
            eventsChanged |= !combat.isEmpty() || !moves.isEmpty();
            combat.clear();
            moves.clear();
        }
        return logRound;
    }

    /** Special reports replace the accumulated, provisional phase text; they are not appended as new events. */
    void live(String html, int round, GamePhase phase) {
        if (!phase.isReport() && !html.equals(liveHtml)) {
            liveHtml = html;
            liveRound = Math.max(1, displayRound(round));
            livePhase = phase;
            liveChanged = true;
        }
    }

    /**
     * The round the player sees for MegaMek's {@code round} (user item 30). MegaMek counts from round 0, the
     * start-of-game deployment, so its first combat round is 1; but when nothing deploys at the start, round 0 is
     * already a combat round, which its initiative marker says ("Initiative Phase for Round #0" instead of "... for
     * Start of Game Deployment"), and the count moves up by one. So the first combat round is 1 in both kinds of game.
     * {@code firstReports} is the first list of MegaMek's report history, which holds the reports of its rounds 0 and
     * 1 (GameReports); until it holds the round-0 initiative, MegaMek's count stands. 0 before the first combat round:
     * the start-of-game deployment and the phases before it.
     */
    static int displayRound(int round, List<Report> firstReports) {
        boolean combatFromZero = firstReports.stream().filter(report -> PHASES.get(report.messageId)
              == GamePhase.INITIATIVE).findFirst().filter(report -> COMBAT_INITIATIVES.contains(report.messageId))
              .isPresent();
        return Math.max(0, combatFromZero ? round + 1 : round);
    }

    /** The round the player sees by the history of the last capture, for the events reported between captures. */
    private int displayRound(int round) {
        return displayRound(round, rounds.isEmpty() ? List.of() : rounds.getFirst().source());
    }

    Snapshot capture(List<List<Report>> history, int round, GamePhase phase) {
        return capture(history, round, phase, List.of(), id -> null);
    }

    /**
     * EDT: the log of the client's report history, with the artillery rounds in the air the client lists. The
     * entries and events carry the round the player sees; the start-of-game deployment is logged with round 1, as
     * GameReports stores it.
     */
    Snapshot capture(List<List<Report>> history, int round, GamePhase phase, List<RoundsInAirDialog.Row> inFlight,
          IntFunction<BoardScene.Pixels> icon) {
        List<Report> first = history.isEmpty() ? List.of() : history.getFirst();
        int shown = displayRound(round, first);
        keepRound(history.isEmpty() ? 0 : Math.max(1, shown));
        boolean changed = history.size() != rounds.size();
        List<Round> next = new ArrayList<>();
        for (int index = 0; index < history.size(); index++) {
            List<Report> reports = history.get(index);
            Round old = index < rounds.size() ? rounds.get(index) : null;
            if (old != null && old.source() == reports && old.size() == reports.size()) {
                next.add(old);
            } else {
                changed = true;
                // GameReports keeps MegaMek's rounds 0 and 1 in its first list (RoundFormat splits them) and round n
                // in list n - 1.
                next.add(format(index == 0 ? 1 : displayRound(index + 1, first), reports));
            }
        }
        if (!liveHtml.isEmpty() && (livePhase != phase || liveRound != Math.max(1, shown) || changed)) {
            liveHtml = "";
            liveChanged = true;
        }
        if (changed || liveChanged || eventsChanged || !inFlight.equals(snapshot.inFlight())
              || snapshot.round() != shown || snapshot.phase() != phase) {
            rounds = List.copyOf(next);
            List<Entry> reported = new ArrayList<>();
            List<PsrItem> psr = new ArrayList<>();
            List<CritItem> crits = new ArrayList<>();
            for (Round saved : rounds) {
                reported.addAll(saved.entries());
                saved.psr().stream().filter(item -> item.round() == eventRound).forEach(psr::add);
                saved.crits().stream().filter(item -> item.round() == eventRound).forEach(crits::add);
            }
            List<Entry> entries = new ArrayList<>(link(reported, combat, eventRound));
            if (!liveHtml.isBlank()) {
                Text text = linkedText(liveHtml);
                entries.add(new Entry(liveRound, phase.localizedName(), phase, "Live resolution", text.text(),
                      List.copyOf(units(liveHtml).values()), rolls(text), text.links(), Kind.OTHER, Entity.NONE,
                      Entity.NONE, null, List.of(), false, false, null, null, null, null, null, null, null, false,
                      false));
            }
            Map<Integer, BoardScene.Pixels> icons = new LinkedHashMap<>();
            entries.stream().flatMap(entry -> entry.units().stream()).map(Unit::id).distinct().forEach(id -> {
                // A historical thumbnail remains stable when the unit disappears or is destroyed later.
                BoardScene.Pixels image = snapshot.icons().get(id);
                if (image == null) {
                    image = icon.apply(id);
                }
                if (image != null) {
                    icons.put(id, image);
                }
            });
            snapshot = new Snapshot(shown, phase, entries, icons, combat, psr, moves, inFlight, crits);
            liveChanged = false;
            eventsChanged = false;
        }
        return snapshot;
    }

    /**
     * Links each weapon and physical entry of {@code round}, the events' round, to the first unlinked event with the
     * same phase, attacker, target and attack family, and for a weapon the weapon the entry starts with. Entries and
     * events each keep their own order (K7). An attack MegaMek reports as impossible was not made and sends no event,
     * so it takes none.
     */
    private static List<Entry> link(List<Entry> entries, List<CombatEvent> events, int round) {
        if (events.isEmpty()) {
            return entries;
        }
        Map<LinkKey, List<CombatEvent>> open = new HashMap<>();
        for (CombatEvent event : events) {
            open.computeIfAbsent(new LinkKey(event.phase(), event.attackerId(), event.targetId(),
                  event.kind() == ResolvedAttack.Kind.SHOT), key -> new ArrayList<>()).add(event);
        }
        List<Entry> linked = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            boolean weapon = entry.kind() == Kind.WEAPON;
            List<CombatEvent> candidates = entry.round() == round && (weapon || entry.kind() == Kind.PHYSICAL)
                  && entry.notFired() == null
                  ? open.get(new LinkKey(entry.gamePhase(), entry.attackerId(), entry.targetId(), weapon)) : null;
            CombatEvent match = candidates == null ? null : candidates.stream()
                  .filter(event -> !weapon || startsWithName(entry.text(), event.weapon())).findFirst().orElse(null);
            if (match != null) {
                candidates.remove(match);
            }
            linked.add(match == null ? entry : entry.linked(match.id()));
        }
        return linked;
    }

    /**
     * An attack line starts with the weapon's name, and the next character is not a letter or digit: the handlers
     * append " (Clan)", a group count, the ammunition or a submunition. A weapon whose name extends another's ("LRM 20
     * (OS)", "Machine Gun Array") therefore also matches the shorter name's event.
     */
    private static boolean startsWithName(String text, String weapon) {
        return !weapon.isEmpty() && text.startsWith(weapon)
              && (text.length() == weapon.length() || !Character.isLetterOrDigit(text.charAt(weapon.length())));
    }

    /**
     * One list of MegaMek's report history, formatted with the round the player sees, {@code round}. A further combat
     * initiative in the list starts the next round: GameReports keeps MegaMek's rounds 0 and 1 in its first list, both
     * combat rounds when nothing deploys at the start.
     */
    static Round format(int round, List<Report> reports) {
        // The subject field is transient on the wire. Use only identities actually disclosed in the report text.
        List<String> rendered = reports.stream().map(report -> new Report(report).text()).toList();
        Map<Integer, Unit> known = new LinkedHashMap<>();
        rendered.forEach(html -> known.putAll(units(html)));
        List<Unit> aliases = known.values().stream().filter(unit -> known.values().stream()
                    .filter(other -> other.name().equals(unit.name())).count() == 1)
              .sorted(Comparator.comparingInt((Unit unit) -> unit.name().length()).reversed()).toList();
        RoundFormat format = new RoundFormat(round, reports, rendered, aliases);
        for (int index = 0; index < reports.size(); index++) {
            format.report(index);
        }
        format.flush();
        return new Round(reports, reports.size(), List.copyOf(format.entries), List.copyOf(format.psr),
              List.copyOf(format.crits));
    }

    /** Splits one round's reports into entries at phase, actor, attack, subject and paragraph boundaries. */
    private static final class RoundFormat {
        private int round;
        private int combatInitiatives;
        private final List<Report> reports;
        private final List<String> rendered;
        private final List<Unit> aliases;
        private final List<Entry> entries = new ArrayList<>();
        private final List<PsrItem> psr = new ArrayList<>();
        private final List<CritItem> crits = new ArrayList<>();
        private final List<Integer> parts = new ArrayList<>();
        private final Map<Integer, Unit> involved = new LinkedHashMap<>();
        private String phase = "General";
        private GamePhase gamePhase = GamePhase.UNKNOWN;
        private Kind phaseKind = Kind.OTHER;
        private String heading = "";
        private Map<Integer, Unit> actor = Map.of();
        private boolean lineEnded = true;

        private RoundFormat(int round, List<Report> reports, List<String> rendered, List<Unit> aliases) {
            this.round = round;
            this.reports = reports;
            this.rendered = rendered;
            this.aliases = aliases;
        }

        private void report(int index) {
            Report report = reports.get(index);
            int id = report.messageId;
            String html = rendered.get(index);
            GamePhase nextPhase = PHASES.get(id);
            Map<Integer, Unit> named = units(html);
            boolean header = ACTOR_HEADERS.contains(id) || id == 3900 || id == 3901;
            boolean lineStart = actor.isEmpty() && lineEnded;
            boolean newSubject = lineStart && !named.isEmpty() && !involved.keySet().containsAll(named.keySet());
            // A line whose first datum, the subject's name, the server hid (double blind) is about an undisclosed
            // unit: it never joins a disclosed unit's entry, and its heat or PSR block starts an entry of its own.
            boolean hiddenSubject = lineStart && named.isEmpty() && report.dataCount() > 0 && report.data(0) == null
                  && (!involved.isEmpty() || id == HEAT_REPORT || PSR_SUBJECTS.contains(id));
            if (nextPhase != null || header || ATTACK_STARTS.contains(id) || newSubject || hiddenSubject) {
                flush();
            }
            if (COMBAT_INITIATIVES.contains(id) && ++combatInitiatives > 1) {
                round++;
            }
            if (nextPhase != null) {
                phase = SECTIONS.contains(id) ? compact(html) : nextPhase.localizedName();
                gamePhase = nextPhase;
                phaseKind = KINDS.getOrDefault(id, Kind.OTHER);
                heading = "";
                actor = Map.of();
                return;
            }
            if (header) {
                heading = compact(html);
                actor = named;
                involved.putAll(actor);
                return;
            }
            involved.putAll(actor);
            involved.putAll(named);
            parts.add(index);
            lineEnded = report.newlines > 0;
            if (report.newlines > 1 || id == 1210) {
                flush();
            }
        }

        private void flush() {
            Text linked = linkedText(parts.stream().map(index -> rendered.get(index) + ' ')
                  .collect(Collectors.joining()));
            String text = linked.text();
            if (!text.isBlank()) {
                aliased(text).forEach(unit -> involved.putIfAbsent(unit.id(), unit));
                entries.add(entry(text, rolls(linked), linked.links()));
            }
            parts.clear();
            involved.clear();
        }

        /**
         * The units a plain text names. Some older physical-attack messages contain plain names. Match only
         * unambiguous names already disclosed in this round, never an entity lookup that could reveal a concealed or
         * sensor-only contact.
         */
        private List<Unit> aliased(String text) {
            List<Unit> named = new ArrayList<>();
            String remaining = text;
            for (Unit unit : aliases) {
                Matcher alias = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(unit.name())
                      + "(?![\\p{L}\\p{N}])").matcher(remaining);
                if (alias.find()) {
                    named.add(unit);
                    remaining = alias.replaceAll("");
                }
            }
            return named;
        }

        /**
         * The attack's target: the first other unit its attack start names. Otherwise none when the start may name a
         * hex or a building, else the first other unit the entry names (the start's plain name is no unique alias).
         */
        private int target(int attackerId) {
            int start = parts.getFirst();
            String html = rendered.get(start);
            Stream<Integer> fallback = PLACE_TARGETS.contains(reports.get(start).messageId) ? Stream.empty()
                  : involved.keySet().stream();
            return Stream.of(units(html).keySet().stream(), aliased(compact(html)).stream().map(Unit::id), fallback)
                  .flatMap(ids -> ids).filter(id -> id != attackerId).findFirst().orElse(Entity.NONE);
        }

        /** Classifies the current parts and records their PSR items. */
        private Entry entry(String text, String rolls, List<Link> links) {
            Kind kind = parts.stream().map(index -> KINDS.get(reports.get(index).messageId)).filter(Objects::nonNull)
                  .findFirst().orElse(phaseKind);
            boolean attack = kind == Kind.WEAPON || kind == Kind.PHYSICAL;
            int attackerId = attack && !actor.isEmpty() ? actor.keySet().iterator().next() : Entity.NONE;
            int targetId = attack ? target(attackerId) : Entity.NONE;
            List<Long> items = new ArrayList<>();
            boolean critical = false;
            boolean heatAlert = false;
            boolean locationDestroyed = false;
            boolean unitDestroyed = false;
            String notFired = null;
            String ammo = null;
            Integer heat = null;
            Integer heatGained = null;
            Integer heatSunk = null;
            int subject = Entity.NONE;
            String reasons = "";
            String critLocation = "";
            for (int index : parts) {
                Report report = reports.get(index);
                int id = report.messageId;
                Map<Integer, Unit> named = units(rendered.get(index));
                String result = report.data(report.dataCount() - 1);
                if (PSR_SUBJECTS.contains(id) || !named.isEmpty()) {
                    subject = named.isEmpty() ? Entity.NONE : named.keySet().iterator().next();
                }
                // A critical hit belongs to the unit the entry last named (an attack's damage line names its target).
                boolean check = id == CRITICAL_CHECK && index + 1 < reports.size()
                      && reports.get(index + 1).messageId == CRITICAL_ROLL;
                if (check || id == CRITICAL_TRANSFER) {
                    critLocation = compact(Objects.toString(report.data(0), ""));
                } else if (subject != Entity.NONE
                      && (id == CRITICAL_SLOT || id == CRITICAL_CHECK || CRITICAL_EFFECTS.contains(id))) {
                    String hit = CRITICAL_EFFECTS.contains(id) ? compact(rendered.get(index))
                          : compact(Objects.toString(report.data(0), ""));
                    if (!hit.isEmpty()) {
                        crits.add(new CritItem(((long) round << 32) | index, round, gamePhase, subject, critLocation,
                              hit));
                    }
                }
                if (PSR_REASONS.contains(id)) {
                    reasons = compact(Objects.toString(result, ""));
                }
                boolean automatic = PSR_AUTOMATIC_FAILURES.contains(id);
                if (automatic || PSR_ROLLS.containsKey(id)) {
                    boolean passed = !automatic && "true".equals(result);
                    Integer psrTarget = automatic ? Integer.valueOf(TargetRoll.AUTOMATIC_FAIL)
                          : number(report, PSR_ROLLS.get(id));
                    Integer psrRoll = automatic ? Integer.valueOf(0) : number(report, report.dataCount() - 2);
                    critical |= !passed;
                    if (subject != Entity.NONE && psrTarget != null && psrRoll != null) {
                        PsrItem item = new PsrItem(((long) round << 32) | index, round, gamePhase, subject, psrTarget,
                              psrRoll, passed, reasons);
                        psr.add(item);
                        items.add(item.id());
                    }
                }
                // K5 as the mock counts it (a failed check, or heat of 14 and more), from the report ids alone.
                if (HEAT_CHECKS.contains(id) && !"true".equals(result) || SHUTDOWN_RANGE.contains(id)) {
                    heatAlert = true;
                    critical = true;
                }
                if (id == HEAT_REPORT) {
                    heat = number(report, HEAT_REPORT_HEAT);
                    heatGained = number(report, HEAT_REPORT_GAINED);
                    heatSunk = number(report, HEAT_REPORT_SUNK);
                }
                if (IMPOSSIBLE.contains(id)) {
                    notFired = compact(Objects.toString(report.data(0), ""));
                }
                if (id == AMMO_ATTACK && report.data(1) != null) {
                    ammo = compact(report.data(1));
                }
                locationDestroyed |= id == LOCATION_DESTROYED;
                unitDestroyed |= id == UNIT_DESTROYED;
                critical |= WARNING.matcher(rendered.get(index)).find();
            }
            return new Entry(round, phase, gamePhase, heading, text, List.copyOf(involved.values()), rolls, links,
                  kind, attackerId, targetId, null, items, critical, heatAlert, first(TARGET_NUMBERS), first(ROLLS),
                  notFired, ammo, heat, heatGained, heatSunk, locationDestroyed, unitDestroyed);
        }

        /** The number at the given data index of the first part listed in {@code indexes}; null if none. */
        private Integer first(Map<Integer, Integer> indexes) {
            return parts.stream().map(reports::get).filter(report -> indexes.containsKey(report.messageId))
                  .findFirst().map(report -> number(report, indexes.get(report.messageId))).orElse(null);
        }
    }

    private static Map<Integer, Kind> kinds() {
        Map<Integer, Kind> kinds = new HashMap<>();
        Set.of(1000, 1005, 1010).forEach(id -> kinds.put(id, Kind.INITIATIVE));
        kinds.put(2000, Kind.MOVE);
        kinds.put(5000, Kind.HEAT);
        PSR_SUBJECTS.forEach(id -> kinds.put(id, Kind.PSR));
        Set.of(3115, 3116, 3117, 3119, 3120, 3124, 3126).forEach(id -> kinds.put(id, Kind.WEAPON));
        Set.of(4010, 4055, 4070, 4085, 4110, 4145, 4146, 4155, 4210, 4212, 4246, 4280, 4290, 4295, 4305, 4550, 4560,
              4580, 9030).forEach(id -> kinds.put(id, Kind.PHYSICAL));
        return Map.copyOf(kinds);
    }

    /** The number a report shows at data {@code index}; null when hidden or not a number. */
    private static Integer number(Report report, int index) {
        String value = report.data(index);
        try {
            return value == null ? null : Integer.valueOf(compact(value));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** Keep link ranges through HTML cleanup and whitespace compaction, including links with HTML in their tooltip. */
    static Text linkedText(String html) {
        Matcher anchors = LINK.matcher(html);
        List<String> targets = new ArrayList<>();
        String marked = anchors.replaceAll(match -> {
            targets.add(match.group(2));
            return Matcher.quoteReplacement("\uE000" + match.group(3) + "\uE001");
        });
        String plain = compact(marked);
        StringBuilder text = new StringBuilder();
        List<Link> links = new ArrayList<>();
        int start = 0;
        int index = 0;
        for (char character : plain.toCharArray()) {
            if (character == '\uE000') {
                start = text.length();
            } else if (character == '\uE001') {
                String target = targets.get(index++);
                int id = target.startsWith(Report.ENTITY_LINK)
                      ? Integer.parseInt(target.substring(Report.ENTITY_LINK.length())) : -1;
                String detail = id < 0 ? compact(target.substring(Report.TOOLTIP_LINK.length())) : "";
                if (!text.substring(start).contains(Report.OBSCURED_STRING)) {
                    links.add(new Link(start, text.length(), id, detail));
                }
            } else {
                text.append(character);
            }
        }
        return new Text(text.toString(), List.copyOf(links));
    }

    private static Map<Integer, Unit> units(String html) {
        Map<Integer, Unit> result = new LinkedHashMap<>();
        Matcher matcher = UNIT_LINK.matcher(html);
        while (matcher.find()) {
            int id = Integer.parseInt(matcher.group(1));
            String name = compact(matcher.group(2));
            if (!name.isBlank() && !name.contains(Report.OBSCURED_STRING)) {
                result.putIfAbsent(id, new Unit(id, name));
            }
        }
        return result;
    }

    private static String compact(String html) {
        return GpuMenuCommands.plainText(LINK.matcher(html).replaceAll("$3"))
              .replace("&quot;", "\"").replace("&#39;", "'")
              .replace("&apos;", "'").replaceAll("(?m)^\\s*-{3,}\\s*$", "")
              .replaceAll("[\\t\\x0B\\f\\r ]+", " ").replaceAll(" *\\n\\s*", "\n").strip();
    }

    private static String rolls(Text text) {
        Set<String> details = new LinkedHashSet<>();
        for (Link link : text.links()) {
            if (!link.detail().isBlank()) {
                details.add(text.text().substring(link.start(), link.end()) + ": " + link.detail());
            }
        }
        return String.join("\n", details);
    }
}
