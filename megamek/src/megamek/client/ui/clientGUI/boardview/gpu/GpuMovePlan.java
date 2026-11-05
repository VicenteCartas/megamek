/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.client.ui.SharedUtility;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.clientGUI.boardview.spriteHandler.MovementEnvelopeSpriteHandler;
import megamek.client.ui.panels.phaseDisplay.MovementDisplay;
import megamek.client.ui.panels.phaseDisplay.commands.MoveCommand;
import megamek.client.ui.widget.MegaMekButton;
import megamek.common.Hex;
import megamek.common.ManeuverType;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.enums.MoveStepType;
import megamek.common.game.Game;
import megamek.common.moves.MountPathHelper;
import megamek.common.moves.MovePath;
import megamek.common.moves.MovePathSummary;
import megamek.common.moves.MoveStep;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Infantry;
import megamek.common.units.Terrains;

/**
 * EDT service for the local movement plan: an immutable route snapshot and guarded movement commands, and the hold
 * mode that holds every own unit left on the next own turns of a movement phase.
 */
final class GpuMovePlan implements AutoCloseable {
    /** The undo steps the plan keeps, as many as the prototype's history. */
    static final int UNDO_LIMIT = 60;
    /** No turn held yet in this hold. */
    private static final int NO_TURN = Integer.MIN_VALUE;
    /** The step types a path may start with before its route (see {@link #base}). */
    private static final Set<MoveStepType> START_STEPS = EnumSet.of(MoveStepType.START_JUMP,
          MoveStepType.JUMP_MEK_MECHANICAL_BOOSTER, MoveStepType.DEPLOY);

    /**
     * The gear the planner uses; AUTO lets the route choose walking, running or jumping. Outside the planner, TARGET is
     * a charge, DFA or ram gear, whose next board click MegaMek takes as the target, and OTHER any other gear.
     */
    enum Mode { AUTO, WALK, RUN, JUMP, BACK, TARGET, OTHER }

    /** The movement band of a step or envelope hex; BACK and BOOST are only used for route steps. */
    enum Band { WALK, BACK, RUN, BOOST, SPRINT, JUMP, ILLEGAL }

    /** One route step at its absolute level. */
    record Step(Coords coords, int boardId, float level, int facing, Band band, boolean airborne) { }

    /**
     * The plan of the acting unit during the local movement turn; otherwise {@link #idle} (EMPTY unless a hold is on).
     * {@code route} is a copy of the phase display's planned path after the initial mode markers;
     * {@code hover} has the same shape while {@code route} is empty.
     * {@code pins} retain the complete pinned steps, including floors; {@code destination} is null without a route,
     * {@code facing} is the path's final facing. {@code cost}, {@code budget}, {@code type}, {@code heat}, {@code tmm}
     * and {@code warnings}
     * describe the whole path (walk, run and jump MP come from the battle status). {@code envelope} is banded for the
     * mode whether or not the board shows envelopes. {@code holdingRemaining} is, while "Hold all remaining units" is
     * on, the local player's units MegaMek has not moved yet in the held movement phase, on every turn of it (the
     * other players' turns included); 0 when no hold is on. {@code planner} is false for movement the HUD leaves to
     * MegaMek's board clicks (G20: aerospace units, the other gears, a hex the display picks); {@code envelope} is
     * then the display's last one, and none during a hex pick. {@code mounts} are the units a click boards: in the
     * planner's walk gear, the transports on its board the unit could mount ({@code Compute.canMount}) and may not
     * stand with, beside which MegaMek's click stops the path for its Mount command
     * ({@code MountPathHelper.trimToMountableTransport}). {@code facingMatters} is false for a unit that takes no turn
     * commands ({@link #facingMatters}).
     */
    record Snapshot(boolean active, boolean planner, boolean external, int entityId, Mode mode, boolean explicit,
          String gearLabel, List<Step> route, List<Step> hover, List<Step> pins, Coords destination, int facing,
          int cost, int budget, EntityMovementType type, String typeLabel, boolean auto, int heat, int tmm,
          boolean legal, List<String> warnings, boolean canUndo, boolean canPin, boolean facingMatters,
          Map<Coords, Band> envelope, Set<Integer> mounts, int holdingRemaining) {
        static final Snapshot EMPTY = idle(0);

        /** No plan (not the local movement turn, or no unit selected), with the hold's count. */
        static Snapshot idle(int holdingRemaining) {
            return new Snapshot(false, false, false, Entity.NONE, Mode.AUTO, false, "", List.of(), List.of(),
                  List.of(), null, -1, 0, 0, EntityMovementType.MOVE_NONE, "", false, 0, 0, false, List.of(), false,
                  false, false, Map.of(), Set.of(), holdingRemaining);
        }

        Snapshot {
            route = List.copyOf(route);
            hover = List.copyOf(hover);
            pins = List.copyOf(pins);
            warnings = List.copyOf(warnings);
            envelope = Map.copyOf(envelope);
            mounts = Set.copyOf(mounts);
        }
    }

    /** One path step as the plan compares paths. */
    private record StepKey(MoveStepType type, Coords coords, int boardId, int facing, int elevation, int altitude) { }

    /** A path's gear and steps: what tells the plan's own changes from changes made elsewhere. */
    private record PathKey(int gear, List<StepKey> steps) { }

    /** What an envelope was searched from: the gear and the steps up to the last pin. */
    private record EnvelopeKey(int gear, List<StepKey> start) { }

    /** A wanted hover route: the hovered hex and floor for one plan and mode. */
    private record HoverKey(Coords hex, Integer elevation, PathKey plan, Mode mode) { }

    /** One undo step: the plan before a command. EDT only; never published. */
    private record Entry(MovePath path, List<Integer> pins, Mode landMode, int gear, boolean external) { }

    /** The facts of one path of the plan's unit. */
    private record Facts(List<Step> route, Coords destination, int facing, int cost, int budget,
          EntityMovementType type, int heat, int tmm, boolean legal, List<String> warnings) { }

    private final GpuBoardSource source;
    // The plan of entityId, EDT only. Pins retain MovePath prefixes, including each step's direction;
    // landMode filters the walk/run gear (G10).
    private MovementDisplay display;
    private int entityId = Entity.NONE;
    private Mode landMode = Mode.AUTO;
    private List<Integer> pins = List.of();
    private boolean external;
    private final Deque<Entry> history = new ArrayDeque<>();
    private PathKey lastKey;
    private boolean commanding;
    // Derived values, each with its change key; the path searches run in one coalesced job (E rule 9).
    private PathKey factsKey;
    private Facts facts;
    private EnvelopeKey envelopeKey;
    private MovementDisplay.Envelope envelope = MovementDisplay.Envelope.NONE;
    private MovementDisplay.Envelope bandsSource;
    private Mode bandsMode;
    private Map<Coords, Band> bands = Map.of();
    private HoverKey hoverWanted;
    private HoverKey hoverKey;
    private List<Step> hoverRoute = List.of();
    private boolean searchPending;
    private boolean closed;
    private Snapshot published = Snapshot.EMPTY;
    // Hold all remaining units (G15), EDT only: on from holdAll until Stop, the end of its round's movement phase, the
    // last own unit or a unit that cannot hold. heldTurn is the index of the turn the hold last acted in: the client
    // keeps that turn until the server answers the skip, and the hold must not act in it again.
    private boolean holding;
    private int holdRound;
    private int heldTurn = NO_TURN;
    private boolean holdQueued;

    GpuMovePlan(GpuBoardSource source) {
        this.source = source;
    }

    /**
     * EDT: the plan of the movement display's selected unit while the local player moves it, with the would-be route
     * to {@code hover}, and the hold's count. Returns the previous instance while nothing changed. It reads the
     * display's path only; the envelope from the last pin and the hover route are searched in a job after this
     * capture (rule 9), and until then the unit's previous ones stay. A hold's skip also runs in its own event.
     */
    Snapshot capture(JComponent panel, @Nullable Coords hover, float pointedZ) {
        GpuBoardSource.requireSwingThread();
        MovementDisplay md = (panel instanceof MovementDisplay movement) ? movement : null;
        int held = hold(md);
        Entity entity = acting(md);
        if (entity == null) {
            discard();
            Snapshot idle = (held == 0) ? Snapshot.EMPTY : Snapshot.idle(held);
            if (!idle.equals(published)) {
                published = idle;
            }
            return published;
        }
        if (entity.getId() != entityId) {
            discard();
            entityId = entity.getId();
        }
        display = md;
        PathKey key = key(md);
        follow(md, key);
        MovePath path = md.getPlannedMovement();
        boolean planner = planner(md, entity);
        Mode mode = planner ? mode(md) : targeting(md) ? Mode.TARGET : Mode.OTHER;
        Facts shown = facts(key, path, entity);
        // During a hex pick MegaMek's board shows the pick's hexes instead of an envelope, so the plan shows none.
        Map<Coords, Band> reach = planner ? plannedEnvelope(md, key, entity, mode)
              : md.isSelectingHex() ? Map.of() : bands(md.getLastEnvelope(), entity, mode);
        Integer elevation = floorElevation(hover, source.currentView().getBoardId(), pointedZ);
        List<Step> hovered = (planner && shown.route().isEmpty()) ? hover(md, key, hover, elevation, reach) : List.of();
        boolean explicit = planner && (mode != Mode.AUTO);
        Snapshot next = new Snapshot(true, planner, planner && external, entityId, mode, explicit, label(mode),
              shown.route(), hovered, planner ? pinned(path) : List.of(), shown.destination(), shown.facing(),
              shown.cost(), shown.budget(), shown.type(), typeLabel(shown.type(), mode), planner && (mode == Mode.AUTO),
              shown.heat(), shown.tmm(), shown.legal(), shown.warnings(), planner && canUndo(path),
              planner && pinnable(md, path), facingMatters(entity), reach, planner ? mounts(md, entity) : Set.of(),
              held);
        if (!next.equals(published)) {
            published = next;
        }
        return published;
    }

    // GL-safe commands (B.5). Each is posted through the source's input guard and runs only while the plan's unit
    // still moves in a planner gear in the local movement turn.

    /** G6, G7: plots from the last pin to the hex; with {@code pin} the destination becomes a waypoint. */
    void planTo(Coords hex, int boardId, boolean pin) {
        planTo(hex, boardId, pin, Float.NaN);
    }

    /** Plots to the picked building floor, when the pointer supplied a walkable surface height. */
    void planTo(Coords hex, int boardId, boolean pin, float pointedZ) {
        command(md -> plot(md, hex, boardId, pin, floorElevation(hex, boardId, pointedZ)));
    }

    /** G7: pins the destination ("+ Waypoint"). */
    void pinDestination() {
        command(this::pin);
    }

    /** G9: turns the final facing one hexside, left for a negative {@code direction}. */
    void turn(int direction) {
        command(md -> face(md, (md.getPlannedMovement().getFinalFacing() + (direction < 0 ? 5 : 1)) % 6));
    }

    /** Orient the route's endpoint toward the clicked hex, preserving its destination and pinned waypoints. */
    void faceToward(Coords hex, int boardId) {
        command(md -> {
            MovePath path = md.getPlannedMovement();
            if (hex != null && boardId == path.getFinalBoardId() && !hex.equals(path.getFinalCoords())) {
                face(md, path.getFinalCoords().direction(hex));
            }
        });
    }

    /** G10: an explicit mode, or automatic movement when it is the mode already. */
    void setMode(Mode mode) {
        command(md -> switchMode(md, mode));
    }

    /** G11: undoes the last plan command. */
    void undo() {
        command(this::undoStep);
    }

    /** Esc and "Clear route": back to the mode's start. */
    void clearRoute() {
        command(this::clear);
    }

    /**
     * G15: on the local movement turn, holds the selected unit now and every other own unit on the next own turns of
     * this movement phase, each with the phase display's skip (Hold position). Turns alternate by player, so this is
     * a mode: it starts with a toast and shows its count until it ends (see {@link #hold}).
     */
    void holdAll() {
        source.command(() -> {
            MovementDisplay md = display;
            if (holding || (acting(md) == null)) {
                return;
            }
            Game game = source.currentView().game;
            holding = true;
            holdRound = game.getRoundCount();
            heldTurn = NO_TURN;
            toast(md, ToastLevel.INFO, "GpuBoard.hud.move.holdStarted",
                  GpuBattleStatus.unitsToAct(game, source.currentView().getLocalPlayer()));
            holdTurn(md);
        });
    }

    /** G15: Stop ends the hold. */
    void stopHolding() {
        source.command(() -> holding = false);
    }

    /** EDT: drops the plan, a pending search and the hold. */
    @Override
    public void close() {
        closed = true;
        holding = false;
        discard();
    }

    // ------------------------------------------------------------------ commands

    /**
     * Runs a command body on the EDT behind the source's input guard, while the plan's unit still moves in a planner
     * gear in the local movement turn (B.5 recheck). Whatever path it leaves is the plan's own.
     */
    private void command(Consumer<MovementDisplay> body) {
        source.command(() -> {
            MovementDisplay md = display;
            Entity entity = acting(md);
            if ((entity == null) || (entity.getId() != entityId) || !planner(md, entity)) {
                return;
            }
            follow(md, key(md));
            // A prompt of the display (a jump type, a climb) can open a dialog whose nested loop captures the path
            // half-changed; the plan must not adopt that as a change made elsewhere.
            commanding = true;
            try {
                body.accept(md);
            } finally {
                commanding = false;
                if (md.getPlannedMovement() != null) {
                    lastKey = key(md);
                }
            }
        });
    }

    /**
     * G6, G7: cuts the path back to the last pin and plots to the hex as a click does. A click on the hex the route
     * continues from is ignored unless another floor is selected. An explicit floor must be reached legally;
     * otherwise the previous draft stays. Without a picked floor an illegal tail is cut off with a toast.
     */
    private void plot(MovementDisplay md, Coords hex, int boardId, boolean pin, @Nullable Integer elevation) {
        if (pin && (md.getGear() == MovementDisplay.GEAR_JUMP)) {
            toast(md, "GpuBoard.hud.move.jumpSingleHex");
            return;
        }
        MovePath path = md.getPlannedMovement();
        int anchor = anchor(path);
        if (!pin && hex.equals(position(path, anchor)) && (boardId == board(path, anchor))
              && (elevation == null || elevation == elevation(path, anchor))) {
            return;
        }
        Entry before = entry(md);
        md.truncateTo(anchor);
        if (elevation != null && !path.contains(MoveStepType.JUMP_MEK_MECHANICAL_BOOSTER)) {
            MovePath start = md.getPlannedMovement();
            MovePath floorPath = start.getFinalBoardId() == boardId
                  ? start.findPathToElevation(hex, approach(md), elevation) : null;
            if (floorPath == null || !allowed(mode(md), floorPath.getLastStepMovementType())) {
                restore(md, before);
                toast(md, "GpuBoard.hud.move.floorUnreachable", elevation);
                return;
            }
            md.plotPath(floorPath);
        } else {
            md.plotTo(hex, boardId);
            if (elevation != null && (!hex.equals(md.getPlannedMovement().getFinalCoords())
                  || md.getPlannedMovement().getFinalBoardId() != boardId
                  || md.getPlannedMovement().getFinalElevation() != elevation
                  || !md.getPlannedMovement().isMoveLegal())) {
                restore(md, before);
                toast(md, "GpuBoard.hud.move.floorUnreachable", elevation);
                return;
            }
        }
        MovePath plotted = md.getPlannedMovement();
        MovePath possible = plotted.clone();
        possible.clipToPossible();
        boolean cut = possible.length() < plotted.length();
        MoveStep blocked = cut ? plotted.getStep(possible.length()) : null;
        boolean occupied = (blocked != null) && occupiedDestination(plotted, blocked);
        if (cut) {
            md.truncateTo(possible.length());
        }
        path = md.getPlannedMovement();
        Mode mode = mode(md);
        if ((path.length() > anchor) && !allowed(mode, path.getLastStepMovementType())) {
            restore(md, before);
            toast(md, "GpuBoard.hud.move.notReachable", label(mode).toUpperCase(Locale.ROOT));
            return;
        }
        // A click on a transport: MegaMek's click stopped the path beside it, where its Mount command boards it.
        boolean mount = !cut && !hex.equals(path.getFinalCoords()) && (path.getFinalBoardId() == boardId)
              && (MountPathHelper.mountableTransport(path, hex, boardId, source.currentView().game) != null);
        if ((cut || !hex.equals(path.getFinalCoords())) && !mount) {
            if (occupied) {
                toast(md, "GpuBoard.hud.move.hexOccupied", blocked.getPosition().getBoardNum());
            } else if (mode == Mode.AUTO) {
                toast(md, "GpuBoard.hud.move.beyondRunningMp");
            } else {
                toast(md, "GpuBoard.hud.move.notReachable", label(mode).toUpperCase(Locale.ROOT));
            }
        } else if (pin) {
            addPin(md, path);
        }
        record(md, before);
    }

    /** G7: pins the destination when it moved and is not pinned already; a jump has no waypoints. */
    private void pin(MovementDisplay md) {
        if (md.getGear() == MovementDisplay.GEAR_JUMP) {
            toast(md, "GpuBoard.hud.move.jumpSingleHex");
            return;
        }
        Entry before = entry(md);
        addPin(md, md.getPlannedMovement());
        record(md, before);
    }

    private void addPin(MovementDisplay md, MovePath path) {
        if (pinnable(md, path)) {
            pins = Stream.concat(pins.stream(), Stream.of(path.length())).toList();
        }
    }

    /**
     * G9: without a route, or in a jump, one turn step at the path's end. With a walk or run route, the cheaper legal
     * one of the computed path from the last pin that ends on the destination in the new facing, and the route plus a
     * turn at the destination. Turning at the last pin includes the turn in that pin. Neither legal: the facing stays,
     * with a toast.
     */
    private void face(MovementDisplay md, int wanted) {
        MovePath path = md.getPlannedMovement();
        if (wanted == path.getFinalFacing() || !facingMatters(path.getEntity())
              || !available(md, MoveCommand.MOVE_TURN)) { return; }
        boolean route = path.length() > base(path);
        boolean pinned = !pins.isEmpty() && (pins.getLast() == path.length());
        Entry before = entry(md);
        Mode mode = mode(md);
        MovePath reroute = (route && (mode != Mode.JUMP)) ? reroute(md, path, wanted) : null;
        if ((reroute != null) && cheaper(reroute, path, wanted, mode)) {
            md.plotPath(reroute);
        } else {
            md.faceToward(path.getFinalCoords().translated(wanted), path.getFinalBoardId());
        }
        MovePath after = md.getPlannedMovement();
        if ((after.getFinalFacing() != wanted) || !after.isMoveLegal()
              || !allowed(mode, after.getLastStepMovementType())) {
            restore(md, before);
            toast(md, route ? "GpuBoard.hud.move.noMpToFace" : "GpuBoard.hud.move.noMpToTurn");
            return;
        }
        if (pinned) {
            pins = Stream.concat(pins.stream().limit(pins.size() - 1), Stream.of(after.length())).toList();
        }
        record(md, before);
    }

    /** G9 (a): the cheapest legal path of the envelope from the last pin that ends on the destination in the facing. */
    private @Nullable MovePath reroute(MovementDisplay md, MovePath path, int facing) {
        searchEnvelope(md);
        Coords destination = path.getFinalCoords();
        int boardId = path.getFinalBoardId();
        Mode mode = mode(md);
        return envelope.paths().stream()
              .filter(candidate -> destination.equals(candidate.getFinalCoords())
                    && (candidate.getFinalBoardId() == boardId) && (candidate.getFinalFacing() == facing)
                    && (candidate.getFinalElevation() == path.getFinalElevation())
                    && (candidate.getFinalAltitude() == path.getFinalAltitude())
                    && candidate.isMoveLegal() && allowed(mode, candidate.getLastStepMovementType()))
              .min(Comparator.comparingInt(MovePath::getMpUsed)).orElse(null);
    }

    /** G9: whether the re-route costs less than the route plus a turn at the destination, or that turn is illegal. */
    private static boolean cheaper(MovePath reroute, MovePath path, int facing, Mode mode) {
        MovePath turned = path.clone();
        turned.rotatePathfinder(facing, false, ManeuverType.MAN_NONE);
        return !turned.isMoveLegal() || !allowed(mode, turned.getLastStepMovementType())
              || (reroute.getMpUsed() < turned.getMpUsed());
    }

    /**
     * G10: Walk and Run are MegaMek's walk gear with the plan's filter, Jump and Walk backwards its jump and back-up
     * gears (each through its own command); the same mode again is automatic. Switching between forward and backward
     * movement keeps the route and pins; other mode changes clear them.
     */
    private void switchMode(MovementDisplay md, Mode requested) {
        Mode next = (mode(md) == requested) ? Mode.AUTO : requested;
        MoveCommand command = command(gear(next));
        boolean switching = md.getGear() != gear(next);
        if (switching && !available(md, command)) {
            if (next == Mode.JUMP) {
                toast(md, (md.currentEntity().getAnyTypeMaxJumpMP() > 0) ? "GpuBoard.hud.move.jumpUnavailable"
                      : "GpuBoard.hud.move.noJumpJets");
            }
            return;
        }
        Entry before = entry(md);
        boolean directionChange = ((md.getGear() == MovementDisplay.GEAR_LAND) && (next == Mode.BACK))
              || ((md.getGear() == MovementDisplay.GEAR_BACKUP) && (gear(next) == MovementDisplay.GEAR_LAND));
        if (!directionChange) {
            md.truncateTo(base(md.getPlannedMovement()));
            pins = List.of();
            external = false;
        }
        if (switching) {
            press(md, command);
        }
        if (gear(next) == MovementDisplay.GEAR_LAND) {
            landMode = next;
        }
        record(md, before);
    }

    /**
     * G11: puts back the plan before the last command. A path changed elsewhere is undone as MegaMek's Backspace
     * (UNDO_LAST_STEP) does: its illegal tail, else its last step.
     */
    private void undoStep(MovementDisplay md) {
        MovePath path = md.getPlannedMovement();
        if (!history.isEmpty()) {
            restore(md, history.pop());
        } else if (external && (path.length() > base(path))) {
            MovePath possible = path.clone();
            possible.clipToPossible();
            int length = (possible.length() < path.length()) ? possible.length() : (path.length() - 1);
            md.truncateTo(Math.max(base(path), length));
            adopt(md.getPlannedMovement());
        }
    }

    private void clear(MovementDisplay md) {
        Entry before = entry(md);
        md.truncateTo(base(md.getPlannedMovement()));
        pins = List.of();
        external = false;
        record(md, before);
    }

    private Entry entry(MovementDisplay md) {
        return new Entry(md.getPlannedMovement().clone(), pins, landMode, md.getGear(), external);
    }

    /** Keeps the plan before a command as an undo step when the command changed it; at most UNDO_LIMIT steps. */
    private void record(MovementDisplay md, Entry before) {
        if ((before.gear() != md.getGear()) || (before.landMode() != landMode) || !before.pins().equals(pins)
              || (before.external() != external) || !steps(before.path()).equals(steps(md.getPlannedMovement()))) {
            history.push(before);
            while (history.size() > UNDO_LIMIT) {
                history.removeLast();
            }
        }
    }

    /** Puts a plan back: its gear (through the gear's own command), its path, pins and mode. */
    private void restore(MovementDisplay md, Entry entry) {
        if ((md.getGear() != entry.gear()) && !press(md, command(entry.gear()))) {
            return;
        }
        if (!steps(entry.path()).equals(steps(md.getPlannedMovement()))) {
            md.plotPath(entry.path());
        }
        pins = entry.pins();
        landMode = entry.landMode();
        external = entry.external();
    }

    // ------------------------------------------------------------------ hold (G15)

    /**
     * G15, EDT at every capture: ends the hold with its round's movement phase and once no own unit is left to move;
     * on a new own turn it asks for that turn's skip in an event of its own, never inside a game event this capture
     * may run in (B.1 rule 4). Returns the own units left to move, 0 when no hold is on.
     */
    private int hold(@Nullable MovementDisplay md) {
        if (!holding) {
            return 0;
        }
        Game game = source.currentView().game;
        int left = GpuBattleStatus.unitsToAct(game, source.currentView().getLocalPlayer());
        if (!game.getPhase().isMovement() || (game.getRoundCount() != holdRound) || (left == 0)) {
            holding = false;
            return 0;
        }
        if ((md != null) && !holdQueued && (game.getTurnIndex() != heldTurn)
              && md.getClientGUI().getClient().isMyTurn()) {
            holdQueued = true;
            SwingUtilities.invokeLater(() -> {
                if (holdTurn(md)) {
                    source.refresh();
                }
            });
        }
        return left;
    }

    /**
     * G15, EDT in an event of its own: on a new own turn of the held phase, holds the selected unit with the button
     * the Hold position command (PhaseInfo.skipId) presses, GpuBoardActions.skipButton, which ends the turn. Waits
     * while a dialog is pending or the display ignores input, and a later capture asks again. A unit that cannot hold
     * (its Skip is disabled, or the skip leaves its turn open, as after a declined prompt) ends the hold with a
     * toast. True when it held a unit or ended the hold.
     */
    private boolean holdTurn(MovementDisplay md) {
        holdQueued = false;
        Game game = source.currentView().game;
        if (!holding || !source.acceptsInput() || md.isIgnoringEvents() || !game.getPhase().isMovement()
              || (game.getRoundCount() != holdRound) || (game.getTurnIndex() == heldTurn)
              || !md.getClientGUI().getClient().isMyTurn()) {
            return false;
        }
        heldTurn = game.getTurnIndex();
        if (md.currentEntity() == null) {
            // MegaMek starts a turn without a unit when its "auto-select next unit" is off; its Next unit command
            // picks one. That command is disabled once the turn has ended here and waits for the server.
            press(md, MoveCommand.MOVE_NEXT);
        }
        Entity unit = md.currentEntity();
        if (unit == null) {
            return false;
        }
        MegaMekButton skip = GpuBoardActions.skipButton(md);
        if ((skip != null) && skip.isEnabled()) {
            skip.doClick(0);
        }
        if (holding && (md.currentEntity() != null)) {
            holding = false;
            toast(md, "GpuBoard.hud.move.holdStopped", unit.getShortName());
        }
        return true;
    }

    // ------------------------------------------------------------------ plan state

    /** EDT: the display's selected unit while the local player moves it, else null. */
    private @Nullable Entity acting(@Nullable MovementDisplay md) {
        if (closed || (md == null) || (md.getPlannedMovement() == null)
              || !source.currentView().game.getPhase().isMovement() || !md.getClientGUI().getClient().isMyTurn()) {
            return null;
        }
        return md.currentEntity();
    }

    /** Forgets the plan: a unit switch discards it (G19). */
    private void discard() {
        display = null;
        entityId = Entity.NONE;
        landMode = Mode.AUTO;
        pins = List.of();
        external = false;
        history.clear();
        lastKey = null;
        factsKey = null;
        envelopeKey = null;
        envelope = MovementDisplay.Envelope.NONE;
        hoverWanted = null;
        hoverKey = null;
        hoverRoute = List.of();
    }

    /**
     * Adopts a path changed elsewhere since the plan last saw it (a More command, a Swing key or click, a new
     * selection): the plan turns external and keeps it as it is, and its own undo steps no longer apply.
     */
    private void follow(MovementDisplay md, PathKey key) {
        if (!commanding && !key.equals(lastKey)) {
            history.clear();
            adopt(md.getPlannedMovement());
            lastKey = key;
        }
    }

    /** A path the plan did not plot: pinned to its end, so a click continues it, and undone step by step. */
    private void adopt(MovePath path) {
        int length = path.length();
        external = length > base(path);
        pins = external ? List.of(length) : List.of();
    }

    /**
     * The planner plots for ground units on the board in the walk, jump and back-up gears; other movement keeps
     * plain clicks (G20), and so does a hex the display picks (an escape pod landing or a bridge build), which its own
     * board click selects. A unit that walks on (walk-on deployment) is not on the board yet: MegaMek's click on its
     * entry hex places it first, and the plan then continues from there.
     */
    private static boolean planner(MovementDisplay md, Entity entity) {
        int gear = md.getGear();
        return !entity.isAero() && entity.isDeployed() && !md.isSelectingHex()
              && ((gear == MovementDisplay.GEAR_LAND) || (gear == MovementDisplay.GEAR_JUMP)
              || (gear == MovementDisplay.GEAR_BACKUP));
    }

    /** The charge, DFA and ram gears: MegaMek's next board click chooses the target (MovementDisplay.chooseTarget). */
    private static boolean targeting(MovementDisplay md) {
        int gear = md.getGear();
        return (gear == MovementDisplay.GEAR_CHARGE) || (gear == MovementDisplay.GEAR_DFA)
              || (gear == MovementDisplay.GEAR_RAM);
    }

    /**
     * The transports a click on which boards them: in the walk gear, MegaMek's click on one stops the path beside it
     * (MovementDisplay.currentMove), so the HUD plans to it instead of selecting it. That click trims only a path that
     * cannot end in the transport's hex, so a transport the unit may stand with (infantry with a DropShip, a vehicle
     * with a Small Craft) is left out: a click there would plan a plain stack.
     */
    private Set<Integer> mounts(MovementDisplay md, Entity entity) {
        if (md.getGear() != MovementDisplay.GEAR_LAND) {
            return Set.of();
        }
        Game game = source.currentView().game;
        return game.getEntitiesVector().stream()
              .filter(other -> other.isDeployed() && (other.getPosition() != null)
                    && (other.getBoardId() == entity.getBoardId()) && Compute.canMount(entity, other)
                    && (Compute.stackingViolation(game, entity, entity.getElevation(), other.getPosition(),
                    other.getBoardId(), null, entity.climbMode(), true) != null))
              .map(Entity::getId).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Whether the rules cut the route where it enters its destination because another unit stands there on the
     * ground: the step there is legal in itself but no legal end (an illegal stack), or the running or jumping MP
     * still cover it (a walking Mek entering an enemy Mek's hex). A cut on the way there, or one the MP explain, is
     * not.
     */
    private boolean occupiedDestination(MovePath path, MoveStep blocked) {
        Entity entity = path.getEntity();
        int mp = blocked.isJumping() ? entity.getAnyTypeMaxJumpMP() : entity.getRunMP();
        return (blocked.getPosition() != null) && blocked.getPosition().equals(path.getFinalCoords())
              && (blocked.getBoardId() == path.getFinalBoardId())
              && ((blocked.getMovementType(false) != EntityMovementType.MOVE_ILLEGAL) || (blocked.getMpUsed() <= mp))
              && source.currentView().game.getEntitiesVector(blocked.getPosition(), blocked.getBoardId()).stream()
                    .anyMatch(unit -> (unit.getId() != entityId) && !unit.isAirborne());
    }

    private Mode mode(MovementDisplay md) {
        return switch (md.getGear()) {
            case MovementDisplay.GEAR_JUMP -> Mode.JUMP;
            case MovementDisplay.GEAR_BACKUP -> Mode.BACK;
            default -> landMode;
        };
    }

    private static int gear(Mode mode) {
        return switch (mode) {
            case JUMP -> MovementDisplay.GEAR_JUMP;
            case BACK -> MovementDisplay.GEAR_BACKUP;
            default -> MovementDisplay.GEAR_LAND;
        };
    }

    /** The movement command that selects a planner gear. */
    private static MoveCommand command(int gear) {
        return switch (gear) {
            case MovementDisplay.GEAR_JUMP -> MoveCommand.MOVE_JUMP;
            case MovementDisplay.GEAR_BACKUP -> MoveCommand.MOVE_BACK_UP;
            default -> MoveCommand.MOVE_WALK;
        };
    }

    private static @Nullable MegaMekButton button(MovementDisplay md, MoveCommand command) {
        return md.getActionButtons().stream().filter(button -> command.getCmd().equals(button.getActionCommand()))
              .findFirst().orElse(null);
    }

    /**
     * Whether turning the unit means anything: not for infantry whose weapons all fire all around (a conventional
     * platoon's field guns, and one hitting the deck with them, fire forward), which no attack hits by side either.
     */
    static boolean facingMatters(Entity entity) {
        return !(entity instanceof Infantry) || entity.getWeaponList().stream()
              .anyMatch(weapon -> entity.getWeaponArc(entity.getEquipmentNum(weapon)) != Compute.ARC_360);
    }

    private static boolean available(MovementDisplay md, MoveCommand command) {
        MegaMekButton button = button(md, command);
        return (button != null) && button.isEnabled();
    }

    /** Runs a movement command as its button does, when it is enabled. */
    private static boolean press(MovementDisplay md, MoveCommand command) {
        if (!available(md, command)) {
            return false;
        }
        button(md, command).doClick(0);
        return true;
    }

    /**
     * The steps before any route: a jump's start and its mechanical booster step, and a walk-on unit's placement on
     * its entry hex (MegaMek's DEPLOY step, after the jump's start).
     */
    private static int base(MovePath path) {
        int base = 0;
        while ((base < path.length()) && START_STEPS.contains(path.getStep(base).getType())) {
            base++;
        }
        return base;
    }

    /** The path length a plot continues from: the last pin, else the mode's start steps. */
    private int anchor(MovePath path) {
        return pins.isEmpty() ? base(path) : Math.min(pins.getLast(), path.length());
    }

    /** Where the unit is after the first {@code length} steps of the path. */
    private static Coords position(MovePath path, int length) {
        return (length == 0) ? path.getEntity().getPosition() : path.getStep(length - 1).getPosition();
    }

    private static int board(MovePath path, int length) {
        return (length == 0) ? path.getEntity().getBoardId() : path.getStep(length - 1).getBoardId();
    }

    private static int elevation(MovePath path, int length) {
        return (length == 0) ? path.getEntity().getElevation() : path.getStep(length - 1).getElevation();
    }

    /** Interpret only building surfaces as a requested floor; other terrain keeps the normal hex-click behavior. */
    private @Nullable Integer floorElevation(@Nullable Coords coords, int boardId, float pointedZ) {
        if (coords == null || !Float.isFinite(pointedZ)
              || !source.currentView().game.hasBoardLocation(coords, boardId)) {
            return null;
        }
        Hex hex = source.currentView().game.getBoard(boardId).getHex(coords);
        return hex.containsTerrain(Terrains.BUILDING) ? GpuLosResult.pointedLevel(pointedZ) - hex.getLevel() : null;
    }

    private List<Step> pinned(MovePath path) {
        return pins.stream().filter(length -> length > 0 && length <= path.length())
              .map(length -> drawingStep(path.getStep(length - 1), length == path.length()))
              .toList();
    }

    private boolean canUndo(MovePath path) {
        return !history.isEmpty() || (external && (path.length() > base(path)));
    }

    /** G7: a walk or run destination that cost MP and differs from the last pin's hex or floor can be pinned. */
    private boolean pinnable(MovementDisplay md, MovePath path) {
        return (md.getGear() != MovementDisplay.GEAR_JUMP) && (path.length() > base(path)) && (path.getMpUsed() > 0)
              && (pins.isEmpty() || !position(path, anchor(path)).equals(path.getFinalCoords())
                    || board(path, anchor(path)) != path.getFinalBoardId()
                    || elevation(path, anchor(path)) != path.getFinalElevation());
    }

    /**
     * G10: what an explicit walk or run lets a route be, by its movement type only: walking for Walk, walking or
     * running for Run. Never an illegal path.
     */
    private static boolean allowed(Mode mode, EntityMovementType type) {
        Band band = band(type);
        return (band != Band.ILLEGAL) && switch (mode) {
            case WALK -> band == Band.WALK;
            case RUN -> (band == Band.WALK) || (band == Band.RUN);
            default -> true;
        };
    }

    // ------------------------------------------------------------------ derived values

    private static PathKey key(MovementDisplay md) {
        return new PathKey(md.getGear(), steps(md.getPlannedMovement()));
    }

    private static List<StepKey> steps(MovePath path) {
        return path.getStepVector().stream()
              .map(step -> new StepKey(step.getType(), step.getPosition(), step.getBoardId(), step.getFacing(),
                    step.getElevation(), step.getAltitude()))
              .toList();
    }

    /** The facts of the path, computed again only when it changed. */
    private Facts facts(PathKey key, MovePath path, Entity entity) {
        if (!key.equals(factsKey)) {
            List<Step> route = route(path);
            EntityMovementType type = path.getLastStepMovementType();
            boolean moved = !route.isEmpty();
            facts = new Facts(route, moved ? path.getFinalCoords() : null, path.getFinalFacing(), path.getMpUsed(),
                  GpuBoardSource.movementMP(entity, type), type, MovePathSummary.heat(path),
                  MovePathSummary.tmm(path).getValue(), path.isMoveLegal(), moved ? warnings(path) : List.of());
            factsKey = key;
        }
        return facts;
    }

    /** The path's steps after the mode's start steps, banded by movement type and boost use (last as an end step). */
    private List<Step> route(MovePath path) {
        List<Step> route = new ArrayList<>();
        int last = path.length() - 1;
        for (int index = base(path); index <= last; index++) {
            MoveStep step = path.getStep(index);
            if (step.getPosition() != null) {
                route.add(drawingStep(step, index == last));
            }
        }
        return List.copyOf(route);
    }

    /** Routes and pinned waypoints use the same engine-supplied position and height. */
    private Step drawingStep(MoveStep step, boolean last) {
        Hex hex = source.currentView().game.getBoard(step.getBoardId()).getHex(step.getPosition());
        EntityMovementMode movement = step.getMovementMode();
        boolean flying = (step.getAltitude() > 0)
              || ((movement.isVTOL() || movement.isWiGE()) && (step.getClearance() > 0));
        float level = (step.getAltitude() > 0) ? step.getAltitude()
              : (step.getElevation() + ((hex == null) ? 0 : hex.getLevel()));
        EntityMovementType type = step.getMovementType(last);
        // Match StepSprite: the movement compiler marks each boosted step, including VTOL jet boosters.
        Band band = switch (type) {
            case MOVE_RUN, MOVE_VTOL_RUN, MOVE_OVER_THRUST ->
                  (step.isUsingMASC() || step.isUsingSupercharger()) ? Band.BOOST : Band.RUN;
            case MOVE_JUMP, MOVE_ILLEGAL -> band(type);
            default -> switch (step.getType()) {
                case BACKWARDS, LATERAL_LEFT_BACKWARDS, LATERAL_RIGHT_BACKWARDS -> Band.BACK;
                default -> band(type);
            };
        };
        return new Step(step.getPosition(), step.getBoardId(), level, step.getFacing(), band, flying);
    }

    /** A movement type's band, grouped as GpuBoardSource.movementMP groups the MP the type uses. */
    static Band band(EntityMovementType type) {
        return switch (type) {
            case MOVE_ILLEGAL -> Band.ILLEGAL;
            case MOVE_JUMP -> Band.JUMP;
            case MOVE_SPRINT, MOVE_VTOL_SPRINT -> Band.SPRINT;
            case MOVE_RUN, MOVE_VTOL_RUN, MOVE_SUBMARINE_RUN, MOVE_OVER_THRUST, MOVE_SKID -> Band.RUN;
            default -> Band.WALK;
        };
    }

    /** The reasons of the piloting rolls the path needs (SharedUtility.getPSRList), as the server reports them. */
    private static List<String> warnings(MovePath path) {
        return SharedUtility.getPSRList(path.clone()).stream().map(TargetRoll::getLastPlainDesc).distinct().toList();
    }

    /** The explicit mode's word, also in its refusal toasts; empty for automatic movement and other gears. */
    private static String label(Mode mode) {
        return switch (mode) {
            case WALK -> Messages.getString("GpuBoard.hud.move.walk");
            case RUN -> Messages.getString("GpuBoard.hud.move.run");
            case JUMP -> Messages.getString("GpuBoard.hud.move.jump");
            case BACK -> Messages.getString("GpuBoard.hud.dock.walkBackwards");
            default -> "";
        };
    }

    /** The route's movement as the dock foot and the tip name it; a walking route in an explicit run says so (G10). */
    private static String typeLabel(EntityMovementType type, Mode mode) {
        return Messages.getString(switch (band(type)) {
            case WALK, BACK -> (mode == Mode.RUN) ? "GpuBoard.hud.move.walkRunOnly" : "GpuBoard.hud.move.walk";
            case RUN, BOOST -> "GpuBoard.hud.move.run";
            case SPRINT -> "GpuBoard.hud.move.sprint";
            case JUMP -> "GpuBoard.hud.move.jump";
            case ILLEGAL -> "GpuBoard.hud.move.illegal";
        });
    }

    /**
     * The planner's envelope, from the last pin and banded for the mode. A new one is searched in the job after this
     * capture; until then the unit's previous one stays.
     */
    private Map<Coords, Band> plannedEnvelope(MovementDisplay md, PathKey key, Entity entity, Mode mode) {
        if (!envelopeKey(md, key).equals(envelopeKey)) {
            requestSearch();
        }
        return bands(envelope, entity, mode);
    }

    private EnvelopeKey envelopeKey(MovementDisplay md, PathKey key) {
        return new EnvelopeKey(key.gear(), key.steps().subList(0, anchor(md.getPlannedMovement())));
    }

    /**
     * An envelope banded as the board's envelope is (MovementEnvelopeSpriteHandler.bands with the unit's walk, run
     * and jump MP); an explicit walk shows its band only and an explicit run its whole reach as the run band (G1).
     */
    private Map<Coords, Band> bands(MovementDisplay.Envelope searched, Entity entity, Mode mode) {
        if ((searched != bandsSource) || (mode != bandsMode)) {
            Map<Coords, Band> banded = new HashMap<>();
            if (searched.entityId() == entity.getId()) {
                MovementEnvelopeSpriteHandler.bands(searched.mp(), entity.getWalkMP(), entity.getRunMP(),
                      entity.getAnyTypeMaxJumpMP(), searched.gear()).forEach((coords, swing) -> {
                    Band band = Band.valueOf(swing.name());
                    Band shown = switch (mode) {
                        case WALK -> (band == Band.WALK) ? band : null;
                        case RUN -> (band == Band.SPRINT) ? null : Band.RUN;
                        default -> band;
                    };
                    if (shown != null) {
                        banded.put(coords, shown);
                    }
                });
            }
            bandsSource = searched;
            bandsMode = mode;
            bands = Map.copyOf(banded);
        }
        return bands;
    }

    /**
     * G3: the route a click on the hovered hex would plot, while nothing is plotted. Ordinary hexes use the shown
     * envelope; explicit building floors need their own legality check. Searched in the job after this capture;
     * retain the previous ordinary-hex route while waiting, but never show a preview for a different floor.
     */
    private List<Step> hover(MovementDisplay md, PathKey key, @Nullable Coords hex, @Nullable Integer elevation,
          Map<Coords, Band> reach) {
        MovePath path = md.getPlannedMovement();
        if ((hex == null) || (elevation == null && (!reach.containsKey(hex) || hex.equals(position(path, anchor(path)))))
              || path.contains(MoveStepType.JUMP_MEK_MECHANICAL_BOOSTER)) {
            hoverWanted = null;
            return List.of();
        }
        HoverKey wanted = new HoverKey(hex, elevation, key, landMode);
        if (wanted.equals(hoverKey)) {
            return hoverRoute;
        }
        hoverWanted = wanted;
        requestSearch();
        return ((hoverKey != null) && elevation == null && hoverKey.elevation() == null
              && hoverKey.plan().equals(key) && (hoverKey.mode() == landMode)) ? hoverRoute
              : List.of();
    }

    private void requestSearch() {
        if (!searchPending) {
            searchPending = true;
            SwingUtilities.invokeLater(this::search);
        }
    }

    /**
     * EDT, in its own event after a capture (rule 9): the envelope from the last pin, then the hover route, and a
     * republish. Never inside a dialog's nested loop (B.1 rule 4), and only for the plan the capture saw.
     */
    private void search() {
        searchPending = false;
        MovementDisplay md = display;
        Entity entity = acting(md);
        if ((entity == null) || (entity.getId() != entityId) || !planner(md, entity) || !source.acceptsInput()) {
            return;
        }
        boolean changed = searchEnvelope(md);
        HoverKey wanted = hoverWanted;
        if ((wanted != null) && !wanted.equals(hoverKey) && wanted.plan().equals(key(md))
              && (wanted.mode() == landMode)) {
            // Key first, so a search that fails is not repeated for the same hex.
            hoverKey = wanted;
            hoverRoute = List.of();
            hoverRoute = hoverRoute(md, wanted.hex(), wanted.elevation());
            changed = true;
        }
        if (changed) {
            source.refresh();
        }
    }

    /**
     * Searches the envelope from the last pin (MovementDisplay.computeMovementEnvelope with the steps up to it) unless
     * the current one is from there; true when it searched.
     */
    private boolean searchEnvelope(MovementDisplay md) {
        MovePath path = md.getPlannedMovement();
        EnvelopeKey wanted = envelopeKey(md, key(md));
        if (wanted.equals(envelopeKey)) {
            return false;
        }
        // Key first, so a search that fails is not repeated for the same start.
        envelopeKey = wanted;
        envelope = MovementDisplay.Envelope.NONE;
        MovePath start = path.clone();
        while (start.length() > anchor(path)) {
            // Only the plan's own steps after the last pin are removed.
            start.removeLastStep();
        }
        md.computeMovementEnvelope(start);
        MovementDisplay.Envelope searched = md.getLastEnvelope();
        if ((searched.entityId() == entityId) && (searched.gear() == wanted.gear())) {
            envelope = searched;
        }
        return true;
    }

    /** The route a click on the hex would plot now, without plotting it; empty when the plan would refuse it. */
    private List<Step> hoverRoute(MovementDisplay md, Coords hex, @Nullable Integer elevation) {
        MovePath plotted = md.getPlannedMovement().clone();
        if (plotted.getFinalBoardId() != source.currentView().getBoardId()) {
            return List.of();
        }
        if (elevation != null) {
            plotted = plotted.findPathToElevation(hex, approach(md), elevation);
            if (plotted == null) {
                return List.of();
            }
        } else {
            // The click's search in the walk, jump and back-up gears (MovementDisplay.currentMove).
            plotted.findPathTo(hex, approach(md));
        }
        plotted.clipToPossible();
        return (hex.equals(plotted.getFinalCoords()) && allowed(mode(md), plotted.getLastStepMovementType()))
              ? route(plotted) : List.of();
    }

    private static MoveStepType approach(MovementDisplay md) {
        return md.getGear() == MovementDisplay.GEAR_BACKUP ? MoveStepType.BACKWARDS : MoveStepType.FORWARDS;
    }

    private static void toast(MovementDisplay md, String key, Object... args) {
        toast(md, ToastLevel.WARNING, key, args);
    }

    private static void toast(MovementDisplay md, ToastLevel level, String key, Object... args) {
        if (md.getClientGUI() instanceof ClientGUI gui) {
            gui.addToast(level, (args.length == 0) ? Messages.getString(key) : Messages.getString(key, args));
        }
    }
}
