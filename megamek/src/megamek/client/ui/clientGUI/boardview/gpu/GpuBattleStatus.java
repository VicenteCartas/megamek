/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.UnitAnnotations;
import megamek.client.ui.clientGUI.boardview.UnitStatusWords;
import megamek.client.ui.clientGUI.tooltip.UnitToolTip;
import megamek.client.ui.clientGUI.unitDisplay.HeatEffects;
import megamek.common.ECMInfo;
import megamek.common.Player;
import megamek.common.Report;
import megamek.common.TagInfo;
import megamek.common.actions.AttackAction;
import megamek.common.actions.EntityAction;
import megamek.common.actions.SearchlightAttackAction;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.compute.ComputeECM;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.IArmorState;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.game.GameTurn;
import megamek.common.options.OptionsConstants;
import megamek.common.rules.RulesGame;
import megamek.common.turns.SpecificEntityTurn;
import megamek.common.units.Entity;
import megamek.common.units.EntityClassTurn;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Mek;
import megamek.common.units.Targetable;

/** EDT-owned HUD battle status, copied from the client's game on each capture and never authoritative. */
final class GpuBattleStatus {
    /** The server's initiative line "{name} rolls a {roll}." (TWGameManager.writeInitiativeReport). */
    private static final int INITIATIVE_REPORT = 1015;
    /** One roll of an {@code InitiativeRoll} text: "{total}[{roll}+{bonus}". */
    private static final Pattern ROLL = Pattern.compile("(-?\\d+)\\[(-?\\d+)\\+(-?\\d+)");

    /**
     * Immutable status published with a frame; {@code round} is the round the player sees, 0 before the first combat
     * round (GpuReportLog.displayRound), and {@code turns} is the game's turn list in order. {@code initiative}
     * lists the sides of this round's initiative roll; {@code turnOrderHidden} is true under double blind, where the
     * server does not report the turn order either. {@code incoming} groups the other players' units still to walk
     * on, by edge and side; empty under double blind.
     */
    record Snapshot(int round, GamePhase phase, boolean myTurn, int localPlayerId, int actorId, List<Slot> turns,
          int turnIndex, List<UnitStatus> units, List<InitiativeSide> initiative, boolean turnOrderHidden,
          List<Link> links, Arc arc, List<Incoming> incoming) {
        static final Snapshot EMPTY = new Snapshot(0, GamePhase.UNKNOWN, false, Player.PLAYER_NONE, Entity.NONE,
              List.of(), -1, List.of(), List.of(), false);

        Snapshot {
            turns = List.copyOf(turns);
            units = List.copyOf(units);
            initiative = List.copyOf(initiative);
            links = List.copyOf(links);
            incoming = List.copyOf(incoming);
        }

        /** A status without links between units, an enemy's arcs or other players' walk-ons, as HUD tests build it. */
        Snapshot(int round, GamePhase phase, boolean myTurn, int localPlayerId, int actorId, List<Slot> turns,
              int turnIndex, List<UnitStatus> units, List<InitiativeSide> initiative, boolean turnOrderHidden) {
            this(round, phase, myTurn, localPlayerId, actorId, turns, turnIndex, units, initiative, turnOrderHidden,
                  List.of(), Arc.NONE, List.of());
        }
    }

    /**
     * Another player's units still to walk on from one board edge ({@code zone}, NW 1 to W 8), seen from the local
     * player's {@code side}: how many, and the first and last rounds they enter in. Names stay out of it.
     */
    record Incoming(int zone, Side side, int round, int lastRound, int count) { }

    /**
     * A link the board draws between two listed units: an own or allied unit spotting for indirect fire
     * ({@code Entity.isSpotting}, {@code getSpotTargetId}), or a TAG hit of this turn as the game reports it.
     */
    record Link(int fromId, int toId, Kind kind) {
        enum Kind { SPOT, TAG }
    }

    /**
     * The side of {@code unitId}, standing at {@code origin}, each hex within {@link #REACH} hexes attacks, by
     * {@code Entity.sideTable} (a {@code ToHitData} SIDE_ code, the rule's exact-line split included): the hovered
     * enemy's arcs, else those of the focused target. {@link #NONE} without one.
     */
    record Arc(int unitId, Coords origin, Map<Coords, Integer> sides) {
        static final int REACH = 5;
        static final Arc NONE = new Arc(Entity.NONE, null, Map.of());

        Arc {
            sides = Map.copyOf(sides);
        }
    }

    /**
     * One side of the round's initiative roll as the server reported it: a player, or under team initiative a team of
     * several players ({@code name} "Team 1", {@code rgb} 0). {@code total} = {@code roll} + {@code bonus} of the
     * deciding roll (after a Tactical Genius reroll, the reroll); {@code tieBreaks} are the totals of the rolls that
     * broke a tie. {@code dice} is the kept pair, empty when unknown, which it always is on a client: the server keeps
     * the side rolls to itself. {@code playerIds} are the ids of the players the side stands for, in id order (a
     * team's players who are not observers, as the report lists them), so a turn's {@code Slot.playerId} finds its
     * side.
     */
    record InitiativeSide(String name, int rgb, Side side, int total, int roll, int bonus, List<Integer> dice,
          List<Integer> tieBreaks, List<Integer> playerIds) {
        InitiativeSide {
            dice = List.copyOf(dice);
            tieBreaks = List.copyOf(tieBreaks);
            playerIds = List.copyOf(playerIds);
        }
    }

    /**
     * One activation; {@code entityId} is {@code Entity.NONE} unless the turn names a unit the local player may
     * identify. A turn without a player (only {@code UnloadStrandedTurn}) has an empty name and colour 0.
     */
    record Slot(int playerId, String playerName, int rgb, Side side, int entityId) { }

    enum Side { OWN, ALLY, ENEMY }

    /**
     * Why an own unit still to deploy this round cannot deploy in the local player's next turn: aerospace units have
     * turns of their own after every ground turn ({@code AFTER_GROUND}), infantry and ProtoMeks moving later wait for
     * the player's other units ({@code AFTER_OTHERS}), any other unit for another turn ({@code LATER}). {@code NONE}:
     * it can, or the question does not arise.
     */
    enum Wait { NONE, AFTER_GROUND, AFTER_OTHERS, LATER }

    /**
     * One listed unit as the Swing client shows it. {@code name} is the client's unique short name, {@code chassis}
     * and {@code model} its two parts. {@code canActNow}: the local player's current turn accepts it; {@code pending}:
     * a remaining turn of the phase accepts it; {@code done}: it has acted this phase; {@code destroyed}: destroyed or
     * doomed, as the tooltip says. {@code heatCapacity} is the tooltip's text, empty for units without heat, and
     * {@code moved} is empty before the unit moves. {@code heatEffects} is the Mek heat table's text for its heat,
     * empty when that level has no effect. {@code statusWords} are the words of its board label
     * ({@code UnitStatusWords.statusWords}) and {@code statusTiles} the label's small tiles such as its elevation or
     * "N" for narc pods ({@code UnitStatusWords.statusTiles}). {@code weightClassIndex} is its
     * {@code Entity.getWeightClass()}, an {@code EntityWeightClass} category code and not an order by weight (the
     * support vehicle and large craft codes lie above ASSAULT); {@code declaredAttacks} is the number of attack
     * actions it has declared this phase, including in the physical phase a charge, death from above or ram declared
     * while moving (see {@code declaredAttacks}); for the unit acting in the local player's firing turn it also counts
     * the attacks queued but not yet sent, so it is a declaration only once the unit is {@code done}.
     * {@code ownerId} is its owner's player id. {@code label} is its board label's name in the client's label style
     * ({@code UnitAnnotations.labelName}), empty where that style names none, and {@code marks} are the label's damage
     * tile and bars. {@code twist} is the facing it fires from beside its body's ({@code UnitAnnotations.twistFacing},
     * -1 for none), {@code moveRgb} the client's colour of its movement type ({@code getColorForMovement}) and
     * {@code tmmShown} whether its target movement modifier means something now ({@code UnitAnnotations.tmmShown}).
     * A sensor contact carries only its id, side, position, board and blip icon: its text is empty, its numbers are
     * zero, its facing and twist are -1, its meters are {@code ARMOR_NA}, its owner is {@code Player.PLAYER_NONE}, it
     * has no status words or tiles and its marks are {@link Marks#NONE}. {@code walkOnZone} is the zone of the edge the
     * unit walks on from under walk-on deployment ({@code Board.START_NONE} for a unit that deploys or stands on the
     * board) and {@code walkOnRound} the round in whose movement phase it enters. {@code deployWait} is why an own unit to
     * deploy waits for a later turn of the deployment phase ({@link Wait}).
     */
    record UnitStatus(int id, Side side, boolean sensorContact, String name, String chassis, String model,
          double tons, String weightClass, String formation, String pilot, int gunnery, int piloting, double armor,
          double structure, int heat, int heatRgb, String heatCapacity, int walk, String run, int jump, String moved,
          int mpUsed, int hexesMoved, int facing, int tmm, boolean canActNow, boolean pending, boolean done,
          boolean destroyed, int damageLevel, List<String> destroyedLocations, String heatEffects, Coords position,
          int boardId, BoardScene.Pixels icon, List<UnitStatusWords.StatusWord> statusWords, int weightClassIndex,
          int declaredAttacks, int ownerId, List<UnitStatusWords.StatusWord> statusTiles, String label, Marks marks,
          int twist, int moveRgb, boolean tmmShown, int walkOnZone, int walkOnRound, Wait deployWait) {
        UnitStatus {
            destroyedLocations = List.copyOf(destroyedLocations);
            statusWords = List.copyOf(statusWords);
            statusTiles = List.copyOf(statusTiles);
        }

        /** A unit that does not walk on, as the HUD tests that do not deploy build it. */
        UnitStatus(int id, Side side, boolean sensorContact, String name, String chassis, String model,
              double tons, String weightClass, String formation, String pilot, int gunnery, int piloting, double armor,
              double structure, int heat, int heatRgb, String heatCapacity, int walk, String run, int jump,
              String moved, int mpUsed, int hexesMoved, int facing, int tmm, boolean canActNow, boolean pending,
              boolean done, boolean destroyed, int damageLevel, List<String> destroyedLocations, String heatEffects,
              Coords position, int boardId, BoardScene.Pixels icon, List<UnitStatusWords.StatusWord> statusWords,
              int weightClassIndex, int declaredAttacks, int ownerId, List<UnitStatusWords.StatusWord> statusTiles,
              String label, Marks marks, int twist, int moveRgb, boolean tmmShown) {
            this(id, side, sensorContact, name, chassis, model, tons, weightClass, formation, pilot, gunnery,
                  piloting, armor, structure, heat, heatRgb, heatCapacity, walk, run, jump, moved, mpUsed, hexesMoved,
                  facing, tmm, canActNow, pending, done, destroyed, damageLevel, destroyedLocations, heatEffects,
                  position, boardId, icon, statusWords, weightClassIndex, declaredAttacks, ownerId, statusTiles,
                  label, marks, twist, moveRgb, tmmShown, Board.START_NONE, 0, Wait.NONE);
        }

        /** Whether the unit enters the board by walking on rather than being placed. */
        boolean walksOn() {
            return walkOnZone != Board.START_NONE;
        }

        /** A unit without a twist, movement colour or shown modifier, as the HUD tests that do not draw bases build it. */
        UnitStatus(int id, Side side, boolean sensorContact, String name, String chassis, String model,
              double tons, String weightClass, String formation, String pilot, int gunnery, int piloting, double armor,
              double structure, int heat, int heatRgb, String heatCapacity, int walk, String run, int jump,
              String moved, int mpUsed, int hexesMoved, int facing, int tmm, boolean canActNow, boolean pending,
              boolean done, boolean destroyed, int damageLevel, List<String> destroyedLocations, String heatEffects,
              Coords position, int boardId, BoardScene.Pixels icon, List<UnitStatusWords.StatusWord> statusWords,
              int weightClassIndex, int declaredAttacks, int ownerId, List<UnitStatusWords.StatusWord> statusTiles,
              String label, Marks marks) {
            this(id, side, sensorContact, name, chassis, model, tons, weightClass, formation, pilot, gunnery,
                  piloting, armor, structure, heat, heatRgb, heatCapacity, walk, run, jump, moved, mpUsed, hexesMoved,
                  facing, tmm, canActNow, pending, done, destroyed, damageLevel, destroyedLocations, heatEffects,
                  position, boardId, icon, statusWords, weightClassIndex, declaredAttacks, ownerId, statusTiles,
                  label, marks, -1, 0, false);
        }

        /**
         * A unit labelled with its chassis, or its name without one, and without marks ({@link Marks#NONE}), as the
         * HUD tests that do not show the label style build it.
         */
        UnitStatus(int id, Side side, boolean sensorContact, String name, String chassis, String model,
              double tons, String weightClass, String formation, String pilot, int gunnery, int piloting, double armor,
              double structure, int heat, int heatRgb, String heatCapacity, int walk, String run, int jump,
              String moved, int mpUsed, int hexesMoved, int facing, int tmm, boolean canActNow, boolean pending,
              boolean done, boolean destroyed, int damageLevel, List<String> destroyedLocations, String heatEffects,
              Coords position, int boardId, BoardScene.Pixels icon, List<UnitStatusWords.StatusWord> statusWords,
              int weightClassIndex, int declaredAttacks, int ownerId, List<UnitStatusWords.StatusWord> statusTiles) {
            this(id, side, sensorContact, name, chassis, model, tons, weightClass, formation, pilot, gunnery,
                  piloting, armor, structure, heat, heatRgb, heatCapacity, walk, run, jump, moved, mpUsed, hexesMoved,
                  facing, tmm, canActNow, pending, done, destroyed, damageLevel, destroyedLocations, heatEffects,
                  position, boardId, icon, statusWords, weightClassIndex, declaredAttacks, ownerId, statusTiles,
                  chassis.isEmpty() ? name : chassis, Marks.NONE);
        }
    }

    /**
     * The colours of a unit's board label marks ({@code UnitAnnotations}) as ARGB: its damage-level tile, 0 for none
     * (undamaged, or the client hides damage levels), its armor bar, and its structure bar, 0 for a unit without one.
     * The bars' filled shares are the unit's {@code armor} and {@code structure}. {@link #NONE}: no marks at all.
     */
    record Marks(int damageArgb, int armorArgb, int structureArgb) {
        static final Marks NONE = new Marks(0, 0, 0);

        /** Whether there are marks to show: every listed unit but a sensor contact has an armor bar. */
        boolean shown() {
            return armorArgb != 0;
        }
    }

    /** Total, roll and bonus of one roll in an {@code InitiativeRoll} text. */
    private record Roll(int total, int roll, int bonus) { }

    /** What a unit's arcs depend on: the unit, its hex, its facing and its board. */
    private record ArcKey(int id, Coords position, int facing, int boardId) { }

    private Snapshot snapshot = Snapshot.EMPTY;
    /** The unit and placement the shown arcs were worked out for, or null. */
    private ArcKey arcKey;

    /**
     * Lists the local player's units ({@code Game.getPlayerEntities}, as the Legacy 2D unit overview) and every other
     * {@code visible} unit; units that are not {@code identified} stay anonymous. {@code activeTurn} is the turn the
     * client lets the local player act in now, or null. An unchanged capture returns the previous snapshot, so the
     * render thread can compare by identity.
     */
    Snapshot capture(Game game, Player local, GameTurn activeTurn, int actorId, Predicate<Entity> visible,
          Predicate<Entity> identified, Function<Entity, BoardScene.Pixels> icon) {
        GpuBoardSource.requireSwingThread();
        int localId = local == null ? Player.PLAYER_NONE : local.getId();
        List<GameTurn> turns = game.getTurnsList();
        int turnIndex = game.getTurnIndex();
        // A report phase gives no unit a turn: the initiative report's list is the round's order, which the slots show.
        List<GameTurn> remaining = game.getPhase().isReport() ? List.of()
              : turns.subList(Math.clamp(turnIndex, 0, turns.size()), turns.size());
        List<Slot> slots = new ArrayList<>();
        for (GameTurn turn : turns) {
            Player player = game.getPlayer(turn.playerId());
            Entity named = turn instanceof SpecificEntityTurn specific ? game.getEntity(specific.getEntityNum()) : null;
            slots.add(new Slot(turn.playerId(), player == null ? "" : player.getName(), rgb(player),
                  side(local != null && turn.isValid(localId, game), player, local),
                  named != null && identified.test(named) ? named.getId() : Entity.NONE));
        }
        List<Entity> listed = new ArrayList<>(local == null ? List.of() : game.getPlayerEntities(local, true));
        game.getEntitiesVector().stream().filter(entity -> entity.getOwnerId() != localId && visible.test(entity))
              .forEach(listed::add);
        // The same ECM computation the board view makes for its unit labels (BoardView.updateEcmList)
        List<ECMInfo> ecm = ComputeECM.computeAllEntitiesECMInfo(game.getEntitiesVector());
        Map<Integer, Integer> attacks = declaredAttacks(game, local);
        // A deployment turn belongs to the units that cannot walk on; it takes one that can only when none is left
        // (Game.getFirstDeployableEntityNum), so until then a unit that walks on waits for the movement phase.
        int firstDeployable = game.getPhase().isDeployment() ? game.getFirstDeployableEntityNum(activeTurn)
              : Entity.NONE;
        boolean turnTakesWalkOn = firstDeployable != Entity.NONE
              && game.walksOnInsteadOfDeploying(game.getEntity(firstDeployable));
        // The local player's current or next deployment turn: a unit it does not take waits, whoever's turn it is now.
        GameTurn nextLocal = !game.getPhase().isDeployment() ? null
              : remaining.stream().filter(turn -> turn.isValid(localId, game)).findFirst().orElse(null);
        List<UnitStatus> units = new ArrayList<>();
        for (Entity entity : listed) {
            Side side = side(entity.getOwnerId() == localId, entity.getOwner(), local);
            if (!identified.test(entity)) {
                units.add(new UnitStatus(entity.getId(), side, true, "", "", "", 0, "", "", "", 0, 0,
                      IArmorState.ARMOR_NA, IArmorState.ARMOR_NA, 0, 0, "", 0, "", 0, "", 0, 0, -1, 0, false, false,
                      false, false, Entity.DMG_NONE, List.of(), "", entity.getPosition(), entity.getBoardId(),
                      icon.apply(entity), List.of(), 0, 0, Player.PLAYER_NONE, List.of(), "", Marks.NONE));
                continue;
            }
            // Turn types override either isValidEntity form. The form without the infantry/ProtoMek "move later"
            // check keeps such units pending, because that option only delays their turn.
            boolean pending = remaining.stream().anyMatch(turn -> turn.isValidEntity(entity, game)
                  || turn.isValidEntity(entity, game, false));
            boolean canActNow = activeTurn != null && activeTurn.isValidEntity(entity, game);
            boolean walksOn = game.walksOnInsteadOfDeploying(entity);
            if (walksOn) {
                canActNow &= turnTakesWalkOn;
                pending = canActNow;
            }
            Wait wait = side != Side.OWN || walksOn || !pending || entity.getPosition() != null || nextLocal == null
                  || nextLocal.isValidEntity(entity, game) ? Wait.NONE : waitOf(entity);
            // The local player's view adds the scan and recon camera words (upstream), as the board labels show them.
            List<UnitStatusWords.StatusWord> words = UnitStatusWords.statusWords(entity,
                  ComputeECM.isAffectedByECM(entity, entity.getPosition(), entity.getPosition(), ecm),
                  local);
            List<UnitStatusWords.StatusWord> tiles = UnitStatusWords.statusTiles(entity, game.isOnSpaceMap(entity), local);
            units.add(unit(game, entity, side, canActNow, pending,
                  icon.apply(entity), words, tiles, attacks.getOrDefault(entity.getId(), 0), wait));
        }
        boolean doubleBlind = game.getOptions().booleanOption(OptionsConstants.ADVANCED_DOUBLE_BLIND);
        // The arcs follow the pointer and the focus, which the source knows only later: they stay until arcs() runs.
        Snapshot next = new Snapshot(GpuReportLog.displayRound(game.getRoundCount(), game.getReports(0)),
              game.getPhase(), activeTurn != null, localId, actorId, slots, turnIndex, units, initiative(game, local),
              doubleBlind, links(game, units), snapshot.arc(),
              doubleBlind ? List.of() : incoming(game, localId, local));
        if (!next.equals(snapshot)) {
            snapshot = next;
        }
        return snapshot;
    }

    /**
     * The last captured status with the arcs of {@code target} (null: none), an enemy the source chose from the
     * pointer and the focus; a unit the status does not list as an identified unit shows none. Each hex within
     * {@link Arc#REACH} gets {@code Entity.sideTable}'s side; the arcs are worked out again only when the target, its
     * hex or its facing changed. An unchanged status keeps its identity.
     */
    Snapshot arcs(Entity target) {
        boolean shown = target != null && target.getPosition() != null && snapshot.units().stream()
              .anyMatch(unit -> unit.id() == target.getId() && !unit.sensorContact());
        ArcKey key = shown ? new ArcKey(target.getId(), target.getPosition(), target.getFacing(), target.getBoardId())
              : null;
        Arc arc = !shown ? Arc.NONE : key.equals(arcKey) ? snapshot.arc() : arc(target);
        arcKey = key;
        if (!arc.equals(snapshot.arc())) {
            snapshot = new Snapshot(snapshot.round(), snapshot.phase(), snapshot.myTurn(), snapshot.localPlayerId(),
                  snapshot.actorId(), snapshot.turns(), snapshot.turnIndex(), snapshot.units(), snapshot.initiative(),
                  snapshot.turnOrderHidden(), snapshot.links(), arc, snapshot.incoming());
        }
        return snapshot;
    }

    /** The side of {@code target} each hex within {@link Arc#REACH} of it attacks, on its board. */
    static Arc arc(Entity target) {
        Map<Coords, Integer> sides = new HashMap<>();
        var board = target.getGame() == null ? null : target.getGame().getBoard(target);
        for (int distance = 1; distance <= Arc.REACH; distance++) {
            for (Coords hex : target.getPosition().allAtDistance(distance)) {
                if (board == null || board.contains(hex)) {
                    sides.put(hex, target.sideTable(hex));
                }
            }
        }
        return new Arc(target.getId(), target.getPosition(), sides);
    }

    /**
     * The links between listed units: each own or allied unit that spots for indirect fire, to its spot target, and
     * each TAG hit of this turn the game reports, between two listed units. Sensor contacts link to nothing.
     */
    private static List<Link> links(Game game, List<UnitStatus> units) {
        Map<Integer, UnitStatus> listed = new HashMap<>();
        units.stream().filter(unit -> !unit.sensorContact()).forEach(unit -> listed.put(unit.id(), unit));
        List<Link> links = new ArrayList<>();
        for (UnitStatus unit : listed.values()) {
            Entity entity = game.getEntity(unit.id());
            if (unit.side() != Side.ENEMY && entity != null && entity.isSpotting()
                  && listed.containsKey(entity.getSpotTargetId())) {
                links.add(new Link(unit.id(), entity.getSpotTargetId(), Link.Kind.SPOT));
            }
        }
        for (TagInfo tag : game.getTagInfo()) {
            if (!tag.missed && tag.target instanceof Entity target && listed.containsKey(tag.attackerId)
                  && listed.containsKey(target.getId())) {
                links.add(new Link(tag.attackerId, target.getId(), Link.Kind.TAG));
            }
        }
        links.sort(Comparator.comparingInt(Link::fromId).thenComparingInt(Link::toId));
        return links;
    }

    /**
     * EDT: the own units still to act in this phase, the {@code local} player's (null: none) that MegaMek has not
     * marked done, counted as {@code Game.getMeksLeft} counts Meks. The movement plan's hold and the fire orders'
     * resolve mode count them down.
     */
    static int unitsToAct(Game game, Player local) {
        return local == null ? 0
              : (int) game.getPlayerEntities(local, false).stream().filter(Entity::isSelectableThisTurn).count();
    }

    private static int rgb(Player player) {
        return player == null ? 0 : player.getColour().getColour().getRGB();
    }

    /** Own; otherwise ally unless {@code Player.isEnemyOf} calls the owner an enemy. No owner counts as enemy. */
    private static Side side(boolean own, Player owner, Player local) {
        return own ? Side.OWN : owner == null || owner.isEnemyOf(local) ? Side.ENEMY : Side.ALLY;
    }

    /**
     * The attack actions each unit has declared this phase, one per weapon, from the game's pending actions (the list
     * BoardView.refreshAttacks draws its arrows from). Searchlight illumination is no attack and is not counted. In
     * the physical phase the charges, death from above attacks and rams declared while moving count too, although the
     * board draws no arrow for them: the game keeps them in lists of their own until they resolve at the end of that
     * phase. As in BoardView.addAttack, a handheld weapon's attack counts for the unit carrying it, and an artillery
     * attack aimed at a hex stays secret when its owner is the local player's enemy. During the local player's firing
     * turn the pending actions also hold the attacks FiringDisplay has queued but not yet sent.
     */
    private static Map<Integer, Integer> declaredAttacks(Game game, Player local) {
        Stream<EntityAction> actions = game.getActionsVector().stream();
        if (game.getPhase().isPhysical()) {
            actions = Stream.concat(actions, Stream.concat(game.getChargesVector().stream(),
                  game.getRamsVector().stream()));
        }
        Map<Integer, Integer> counts = new HashMap<>();
        actions.filter(action -> action instanceof AttackAction && !(action instanceof SearchlightAttackAction))
              .map(AttackAction.class::cast)
              .forEach(attack -> {
                  Entity weapon = game.getEntity(attack.getEntityId());
                  Entity attacker = weapon == null ? null : weapon.getAttackingEntity();
                  boolean secret = attack instanceof WeaponAttackAction
                        && attack.getTargetType() == Targetable.TYPE_HEX_ARTILLERY && !friendly(weapon, local);
                  if (attacker != null && !secret) {
                      counts.merge(attacker.getId(), 1, Integer::sum);
                  }
              });
        return counts;
    }

    /**
     * Whether {@code weapon}'s owner is not the local player's enemy, by the rule {@code side} uses
     * ({@code Player.isEnemyOf}: two players without a team are enemies); false when either is unknown.
     */
    private static boolean friendly(Entity weapon, Player local) {
        Player owner = weapon == null ? null : weapon.getOwner();
        return owner != null && !owner.isEnemyOf(local);
    }

    /**
     * The sides of this round's last initiative report. The server rolls team initiative on its own {@code Team}
     * objects and sends only the players, whose own rolls just order the players inside a team, so its report lines
     * are the client's one copy of the side rolls. A team of several players has a line of its own, followed by one
     * line per member, which are skipped. A Tactical Genius reroll appends a second report, which replaces the first.
     * Individual initiative writes other lines and gives no sides.
     */
    private static List<InitiativeSide> initiative(Game game, Player local) {
        List<Report> reports = game.getReports(game.getRoundCount());
        int end = reports.size();
        while (end > 0 && reports.get(end - 1).messageId != INITIATIVE_REPORT) {
            end--;
        }
        int start = end;
        while (start > 0 && reports.get(start - 1).messageId == INITIATIVE_REPORT) {
            start--;
        }
        List<String> teamNames = List.of(Player.TEAM_NAMES);
        List<InitiativeSide> sides = new ArrayList<>();
        int team = Player.TEAM_NONE;
        for (Report report : reports.subList(start, end)) {
            // The exact text the server wrote: a lone player's coloured name, a team's name or a member's name
            String written = Objects.toString(report.data(0), "");
            List<Roll> rolls = rolls(Objects.toString(report.data(1), ""));
            Player player = game.getPlayersList().stream().filter(candidate ->
                  written.equals(candidate.getColorForPlayer()) || written.equals(candidate.getName()))
                  .findFirst().orElse(null);
            if (rolls.isEmpty() || (team > Player.TEAM_NONE && player != null && player.getTeam() == team)) {
                continue; // no roll, or a member's own line below its team's line
            }
            team = Math.max(Player.TEAM_NONE, teamNames.indexOf(written));
            String name = team > Player.TEAM_NONE ? written
                  : player != null ? player.getName() : GpuBoardActions.plainText(written);
            boolean own;
            List<Integer> playerIds;
            if (team > Player.TEAM_NONE) {
                // A team side stands for its players who are not observers, the ones the report lists below it; the
                // first of them stands for the team's relation to the local player
                int teamId = team;
                List<Player> members = game.getPlayersList().stream()
                      .filter(candidate -> candidate.getTeam() == teamId && !candidate.isObserver()).toList();
                own = local != null && local.getTeam() == teamId;
                player = members.isEmpty() ? null : members.getFirst();
                playerIds = members.stream().map(Player::getId).sorted().toList();
            } else {
                own = local != null && local.equals(player);
                playerIds = player == null ? List.of() : List.of(player.getId());
            }
            Roll deciding = rolls.getFirst();
            sides.add(new InitiativeSide(name, team > Player.TEAM_NONE ? 0 : rgb(player), side(own, player, local),
                  deciding.total(), deciding.roll(), deciding.bonus(), List.of(),
                  rolls.subList(1, rolls.size()).stream().map(Roll::total).toList(), playerIds));
        }
        return sides;
    }

    /**
     * The side that won the initiative: the best reported rolls in the order of {@code InitiativeRoll.compareTo} (the
     * higher total, then the higher of each tie-break both sides rolled). Null when there are no sides or two sides
     * share the best rolls.
     */
    static InitiativeSide winner(List<InitiativeSide> sides) {
        InitiativeSide best = sides.stream().max(GpuBattleStatus::compareRolls).orElse(null);
        return best == null || sides.stream().filter(side -> compareRolls(side, best) == 0).count() > 1 ? null : best;
    }

    /**
     * The side that moves first (plan B5): the side that stands for the player of the round's first activation, which
     * under team initiative is the player's team. Null while the turn order is hidden (double blind, plan D4) or
     * empty, or when no side stands for that player.
     */
    static InitiativeSide movesFirst(Snapshot status) {
        if (status.turnOrderHidden() || status.turns().isEmpty()) {
            return null;
        }
        int player = status.turns().getFirst().playerId();
        return status.initiative().stream().filter(side -> side.playerIds().contains(player)).findFirst()
              .orElse(null);
    }

    private static int compareRolls(InitiativeSide first, InitiativeSide second) {
        int compare = Integer.compare(first.total(), second.total());
        for (int i = 0; compare == 0 && i < Math.min(first.tieBreaks().size(), second.tieBreaks().size()); i++) {
            compare = Integer.compare(first.tieBreaks().get(i), second.tieBreaks().get(i));
        }
        return compare;
    }

    /** The rolls of an {@code InitiativeRoll} text in order; a Tactical Genius reroll replaces its original. */
    private static List<Roll> rolls(String text) {
        List<Roll> rolls = new ArrayList<>();
        Matcher matcher = ROLL.matcher(text);
        while (matcher.find()) {
            Roll roll = new Roll(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                  Integer.parseInt(matcher.group(3)));
            if (!rolls.isEmpty() && text.startsWith("](", matcher.start() - 2)) {
                rolls.set(rolls.size() - 1, roll);
            } else {
                rolls.add(roll);
            }
        }
        return rolls;
    }

    /** The unit's weight class name; "" for a weight MegaMek has no class for (it throws then: an unfinished unit). */
    private static String weightClass(Entity entity) {
        try {
            return entity.getWeightClassName();
        } catch (IllegalArgumentException unknown) {
            return "";
        }
    }

    /** The values the Swing unit display and tooltip show; no rule is recomputed here. */
    private static UnitStatus unit(Game game, Entity entity, Side side, boolean canActNow, boolean pending,
          BoardScene.Pixels icon, List<UnitStatusWords.StatusWord> statusWords, List<UnitStatusWords.StatusWord> statusTiles,
          int declaredAttacks, Wait wait) {
        Force formation = game.getForces().getForce(entity);
        boolean tracksHeat = entity.getHeatCapacity() != Entity.DOES_NOT_TRACK_HEAT;
        String heatEffects = "";
        if (entity instanceof Mek mek) {
            boolean tacOpsHeat = game.getOptions().booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_HEAT);
            heatEffects = HeatEffects.getHeatEffects(entity.heat, tacOpsHeat, mek.hasTSM(false));
            // The heat table names every level without an effect with the text of heat 0
            if (heatEffects.equals(HeatEffects.getHeatEffects(0, tacOpsHeat, mek.hasTSM(false)))) {
                heatEffects = "";
            }
        }
        List<String> destroyedLocations = IntStream.range(0, entity.locations()).filter(entity::isLocationBad)
              .mapToObj(entity::getLocationAbbr).toList();
        Color damage = UnitAnnotations.damageColor(entity);
        Marks marks = new Marks(damage == null ? 0 : damage.getRGB(),
              UnitAnnotations.barColor(entity.getArmorRemainingPercent()).getRGB(),
              UnitAnnotations.hasStructureBar(entity)
                    ? UnitAnnotations.barColor(entity.getInternalRemainingPercent()).getRGB() : 0);
        // An entity built without a model name (scenario code, tests) shows as one with a blank model name.
        return new UnitStatus(entity.getId(), side, false, entity.getShortName(), entity.getChassis(),
              Objects.requireNonNullElse(entity.getModel(), ""), entity.getWeight(), weightClass(entity),
              formation == null ? "" : formation.getName(), entity.getCrew().getName(),
              entity.getCrew().getGunnery(), entity.getCrew().getPiloting(), entity.getArmorRemainingPercent(),
              entity.getInternalRemainingPercent(), entity.heat,
              tracksHeat ? GUIPreferences.getInstance().getColorForHeat(entity.heat).getRGB() : 0,
              tracksHeat ? UnitToolTip.getHeatCapacityForDisplay(entity).heatCapacityStr : "", entity.getWalkMP(),
              entity.getRunMPasString(), entity.getJumpMPWithTerrain(),
              entity.moved == EntityMovementType.MOVE_NONE ? "" : entity.getMovementString(entity.moved),
              entity.mpUsed, entity.delta_distance, entity.getFacing(),
              Compute.getTargetMovementModifier(game, entity.getId()).getValue(), canActNow, pending,
              entity.isDone(), entity.isDoomed() || entity.isDestroyed(), entity.getDamageLevel(),
              destroyedLocations, heatEffects, entity.getPosition(), entity.getBoardId(), icon, statusWords,
              entity.getWeightClass(), declaredAttacks, entity.getOwnerId(), statusTiles,
              UnitAnnotations.labelName(entity, GUIPreferences.getInstance().getUnitLabelStyle()), marks,
              UnitAnnotations.twistFacing(entity),
              GUIPreferences.getInstance().getColorForMovement(entity.moved).getRGB(),
              UnitAnnotations.tmmShown(entity, game.getPhase()),
              walkOnZone(entity), Math.max(Math.max(1, entity.getDeployRound()), game.getRoundCount()), wait);
    }

    /** Why a unit waits for a later deployment turn, by the turn classes TWGameManager deals out. */
    private static Wait waitOf(Entity entity) {
        int classCode = EntityClassTurn.getClassCode(entity);
        int aerospace = EntityClassTurn.CLASS_AERO | EntityClassTurn.CLASS_SMALL_CRAFT
              | EntityClassTurn.CLASS_DROPSHIP | EntityClassTurn.CLASS_JUMPSHIP | EntityClassTurn.CLASS_WARSHIP
              | EntityClassTurn.CLASS_SPACE_STATION;
        if ((classCode & aerospace) != 0) {
            return Wait.AFTER_GROUND;
        }
        return (classCode & (EntityClassTurn.CLASS_INFANTRY | EntityClassTurn.CLASS_PROTOMEK)) != 0
              ? Wait.AFTER_OTHERS : Wait.LATER;
    }

    /** The other players' units still to walk on from an edge, grouped by edge and side in zone order. */
    private static List<Incoming> incoming(Game game, int localId, Player local) {
        record Key(int zone, Side side) { }
        Map<Key, int[]> groups = new java.util.TreeMap<>(Comparator.comparingInt(Key::zone)
              .thenComparing(Key::side));
        for (Entity entity : game.getEntitiesVector()) {
            int zone = entity.getOwnerId() == localId ? Board.START_NONE : walkOnZone(entity);
            if (zone < Board.START_NW || zone > Board.START_W) {
                continue;
            }
            int round = Math.max(Math.max(1, entity.getDeployRound()), game.getRoundCount());
            int[] group = groups.computeIfAbsent(new Key(zone, side(false, entity.getOwner(), local)),
                  ignored -> new int[] { Integer.MAX_VALUE, 0, 0 });
            group[0] = Math.min(group[0], round);
            group[1] = Math.max(group[1], round);
            group[2]++;
        }
        List<Incoming> result = new ArrayList<>(groups.size());
        groups.forEach((key, group) -> result.add(new Incoming(key.zone(), key.side(), group[0], group[1], group[2])));
        return result;
    }

    /**
     * The zone of the edge an undeployed unit walks on from under walk-on deployment, whether in this round or a
     * later one; {@code Board.START_NONE} for a unit that is placed instead or already stands on the board.
     */
    private static int walkOnZone(Entity entity) {
        RulesGame rules = Game.rulesManager.getRulesGame();
        int zone = entity.getStartingPos();
        return !entity.isDeployed() && entity.getDeployRound() >= 0 && rules.isWalkOnDeployment()
              && rules.restrictDeploymentWidth(entity.getOwner(), zone) ? zone : Board.START_NONE;
    }
}
