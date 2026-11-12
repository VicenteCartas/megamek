/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import megamek.client.ui.Messages;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;

/**
 * The HUD's cross-component view state on the render thread (rebuild plan B.3, C.5, C.6): what hotkeys, buttons and
 * several components change or read together. It is presentation only and never game state; everything else a view
 * shows lives in the component that shows it.
 */
final class GpuHudState {
    /** The centred panels; opening one closes the others (Help closes Menu, Players and Tuning). */
    enum Dialog { NONE, HELP, MENU, PLAYERS, TUNING }

    /** The overlays the deployment queue's chips show while deploying, one at a time; OFF shows none. */
    enum DeployOverlay {
        DAMAGE, ECM, OFF;

        String label() {
            return Messages.getString("GpuBoard.hud.deploy.overlay." + name());
        }
    }

    /**
     * The unit sheet's tabs with the Unit Display's keys that open them, F1 to F6 (unit panel design 5.1). SYSTEMS is
     * a Mek's critical table and every other unit's systems.
     */
    enum SheetTab {
        STATUS(KeyCommandBind.UD_GENERAL), CREW(KeyCommandBind.UD_PILOT), ARMOR(KeyCommandBind.UD_ARMOR),
        WEAPONS(KeyCommandBind.UD_WEAPONS), SYSTEMS(KeyCommandBind.UD_SYSTEMS), EXTRAS(KeyCommandBind.UD_EXTRAS);

        final KeyCommandBind key;

        SheetTab(KeyCommandBind key) {
            this.key = key;
        }
    }

    /**
     * The round's playback history over the board view's playback, which the board feeds each frame; the dock's
     * transport, the log, the board's pop-ups and the playback keys share it.
     */
    final GpuPlaybackHistory history;
    /** The unit the player inspects; non-owned units have a separate card on the right. */
    int inspected = Entity.NONE;
    /** The equipment number of the weapon armed for click-to-assign, or -1. */
    int armedWeapon = -1;
    /** The weapon and unit of the open aimed shot dialog (H36, R4), null while it is closed. */
    GpuFireOrders.AimTarget aiming;
    Dialog dialog = Dialog.NONE;
    boolean overview;
    /** The deployment strip leaves out the units already placed (GpuDeploymentQueue). */
    boolean hideDeployed;
    /** Deployment's overlay, which Ctrl+wheel chose last; null until it does. */
    DeployOverlay deployOverlay;
    boolean chatOpen;
    /** The unit sheet is open (unit panel design 2.3): one flag, one step of the Esc chain. */
    boolean recordOpen;
    /** The sheet's tab; it never changes with the phase and stays when the sheet closes. */
    SheetTab sheetTab = SheetTab.STATUS;
    /**
     * The ARMOR inspector's location, which the card doll's click and the ARMOR dolls' arrow keys choose: a location's
     * abbreviation, "DC" + arm for a shield, "" for none. Another unit keeps it while it has that location (blink
     * memory).
     */
    String selectedLocation = "";
    /**
     * The location under the pointer (graft 6): a hovered weapon row, critical slot, equipment line or doll region;
     * every visible doll outlines it and the rows and table blocks of it are tinted. "" for none; it never rebuilds the
     * sheet.
     */
    String linkedLocation = "";
    /** The expanded weapon row: its name and its ordinal among the weapons of that name; "" for none. */
    String expandedWeapon = "";
    int expandedOrdinal;
    /** The sheet sections the player collapsed, by their caption keys. */
    final Set<String> collapsedSections = new HashSet<>();
    boolean forcesGrid;
    /** The forces rail is pinned open as the full panel; unpinned it opens only while the pointer is over it. */
    boolean forcesPinned;
    /** The conditions strip is expanded to the full list of conditions. */
    boolean conditionsExpanded;
    /** The nameplate key (SHOW_NAMEPLATES) shows every nameplate: it is held, or toggled on (NameplateMode). */
    boolean allNameplates;
    private boolean logOpen;
    /** A report phase opened the closed log, so the next phase that is no report closes it again. */
    private boolean logAuto;
    private int focus = Entity.NONE;
    /** Last explicit own inspection; a foreign inspection must not replace the left card during playback. */
    private int ownInspection = Entity.NONE;
    /** The player cleared the selection with the card's ✕ (clearSelection). */
    private boolean selectionCleared;
    /** The last status {@link #presented} masked, and the mask, kept so that the snapshot keeps its identity. */
    private GpuBattleStatus.Snapshot maskedStatus;
    private GpuBattleStatus.Snapshot presentedStatus;
    /** The player picked the focus outside the local turn; the next C.5 event ends the pick. */
    private boolean picked;
    private GpuBattleStatus.Snapshot status;
    /** Last status whose automatic selection has actually been presented; live turns may run ahead. */
    private GpuBattleStatus.Snapshot focusStatus;
    /** Last idle frame's unit context. Only presentation uses it; commands never execute from it. */
    private GpuBoardSource.Frame selectionFrame;
    private boolean concealedDuringPlayback;
    private GpuUnitRecord.Snapshot record;
    private GpuUnitRecord.Snapshot ownRecord;
    private boolean playbackBusy;
    private List<GpuBattleStatus.UnitStatus> presentedUnits = List.of();
    private GpuUnitRecord.Snapshot presentedRecord = GpuUnitRecord.Snapshot.EMPTY;
    private GpuUnitRecord.Snapshot presentedOwnRecord = GpuUnitRecord.Snapshot.EMPTY;
    /** The record of the unit deployment peeks at under the pointer, for its reach on the board; GL thread. */
    GpuUnitRecord.Snapshot peekRecord = GpuUnitRecord.Snapshot.EMPTY;

    GpuHudState(GpuPlaybackHistory history) {
        this.history = history;
    }

    /** Opens the panel, or closes it when it is already open. */
    void toggle(Dialog panel) {
        dialog = dialog == panel ? Dialog.NONE : panel;
    }

    boolean logOpen() {
        return logOpen;
    }

    /** The Log utility, its key and the log's close button. A log the phase opened stays closed until reopened. */
    void toggleLog() {
        logOpen = !logOpen;
        logAuto = false;
    }

    /** The own focus unit (C.5), or {@code Entity.NONE}. */
    int focus() {
        return focus;
    }

    /**
     * The unit the card and the record sheet show: the inspected unit, else the own focus unit (C.5) unless the player
     * closed the card. It is also the one unit the forces and contacts lists highlight and the board labels draw in
     * front.
     */
    int cardUnit() {
        return inspected != Entity.NONE ? inspected : selectionCleared ? Entity.NONE : focus;
    }

    /** The left card shows an own inspection or the own selection, independently of a foreign inspection. */
    int ownCardUnit() {
        GpuBattleStatus.UnitStatus unit = presented(inspected);
        if (unit != null && unit.side() == GpuBattleStatus.Side.OWN) { return inspected; }
        if (selectionCleared) { return Entity.NONE; }
        unit = presented(ownInspection);
        if (unit != null && unit.side() == GpuBattleStatus.Side.OWN) { return ownInspection; }
        unit = presented(focus);
        return unit != null && unit.side() == GpuBattleStatus.Side.OWN ? focus : presentedUnits.stream()
              .filter(candidate -> candidate.side() == GpuBattleStatus.Side.OWN)
              .mapToInt(GpuBattleStatus.UnitStatus::id).findFirst().orElse(Entity.NONE);
    }

    /** Only units outside the local player's control occupy the right card, allies included. */
    int otherCardUnit() {
        GpuBattleStatus.UnitStatus unit = presented(inspected);
        return unit != null && unit.side() != GpuBattleStatus.Side.OWN ? inspected : Entity.NONE;
    }

    /**
     * The card's ✕: ends the inspection and clears the selection. No unit is
     * shown or highlighted, and in the local turn no unit acts ({@link #presented}) until the player selects one, or a
     * turn, a phase or the focus moves on.
     */
    void clearSelection() {
        inspected = Entity.NONE;
        ownInspection = Entity.NONE;
        selectionCleared = true;
    }

    /** A unit the player selects ends a cleared selection. */
    void restoreSelection() {
        selectionCleared = false;
        ownInspection = Entity.NONE;
    }

    /** Unit commands wait for playback, or for the player to restore a cleared local selection. */
    boolean turnLocked(GpuBattleStatus.Snapshot status) {
        return playbackBusy || selectionCleared && status.myTurn();
    }

    /** The newest status's phase, which {@link #presented} holds back from the rest of the HUD during playback. */
    GamePhase latestPhase() {
        return status.phase();
    }

    /**
     * The frame as the HUD and the board overlay present it. Outside the local turn there is no fire preview: no
     * to-hit badges or guides from the last own unit while another player moves or fires.
     * A cleared selection has no actor, route, orders or physical options. During playback the displayed
     * unit context stays together, but no unit commands remain available. Chat and notifications stay live.
     */
    GpuBoardSource.Frame presented(GpuBoardSource.Frame frame) {
        if (playbackBusy && selectionFrame != null) {
            GpuHudData latest = frame.panels();
            GpuHudData held = selectionFrame.panels();
            GpuHudData shown = new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, GpuMovePlan.Snapshot.EMPTY,
                  GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, presentedRecord,
                  selectionCleared || concealedDuringPlayback ? GpuFirePreview.Snapshot.NONE : held.preview(), latest.chat(), latest.toasts(),
                  latest.los(), latest.players(), presentedOwnRecord);
            BoardScene scene = frame.scene() == null ? null : frame.scene().withPlaybackSelection(
                  selectionCleared ? Entity.NONE : focus);
            return new GpuBoardSource.Frame(scene, frame.timeline(), null, frame.globalCommands(), frame.tooltip(),
                  frame.hexDetails(), frame.centerRequest(), frame.boardGeneration(),
                  focus == Entity.NONE ? "" : selectionFrame.actorName(),
                  frame.scenarioAtmosphere(), frame.reports(), focusStatus, shown);
        }
        GpuBattleStatus.Snapshot status = frame.status();
        GpuHudData panels = frame.panels();
        boolean locked = turnLocked(status);
        if (!locked && (status.myTurn() || panels.preview() == GpuFirePreview.Snapshot.NONE)) {
            return frame;
        }
        if (locked && status != maskedStatus) {
            maskedStatus = status;
            presentedStatus = new GpuBattleStatus.Snapshot(status.round(), status.phase(), true,
                  status.localPlayerId(), Entity.NONE, status.turns(), status.turnIndex(), status.units(),
                  status.initiative(), status.turnOrderHidden());
        }
        GpuHudData shown = new GpuHudData(panels.phase(), locked ? GpuMovePlan.Snapshot.EMPTY : panels.move(),
              locked ? GpuFireOrders.Snapshot.EMPTY : panels.fire(),
              locked ? GpuPhysicalOptions.Snapshot.EMPTY : panels.physical(), panels.record(),
              GpuFirePreview.Snapshot.NONE, panels.chat(), panels.toasts(), panels.los(), panels.players(),
              panels.ownRecord());
        BoardScene.Context context = frame.context();
        return new GpuBoardSource.Frame(frame.scene(), frame.timeline(),
              locked && context != null && !context.selectionCleared() ? null : context,
              frame.globalCommands(), frame.tooltip(), frame.hexDetails(), frame.centerRequest(),
              frame.boardGeneration(), locked ? "" : frame.actorName(), frame.scenarioAtmosphere(), frame.reports(),
              locked ? presentedStatus : status, shown);
    }

    /**
     * An own unit the player picked outside the local turn becomes the focus, the unit the card and the panels show as
     * selected. It stays the focus even where no turn of the phase accepts it,
     * until the local turn begins or ends; at the next phase's start it stays while a turn of that phase accepts it.
     */
    void pick(int id) {
        focus = id;
        picked = true;
    }

    /**
     * The units every component shows (C.6): held while the playback still has live events to present, those a review
     * holds back included.
     */
    List<GpuBattleStatus.UnitStatus> presentedUnits() {
        return presentedUnits;
    }

    /** The presented unit {@code id} (C.6), or null. */
    GpuBattleStatus.UnitStatus presented(int id) {
        return presentedUnits.stream().filter(unit -> unit.id() == id).findFirst().orElse(null);
    }

    /** The record sheet data, held like the units while it is still the same unit. */
    GpuUnitRecord.Snapshot presentedRecord() {
        return presentedRecord;
    }

    /** Each card uses only its unit's record; a changed selection waits for the EDT capture. */
    GpuUnitRecord.Snapshot presentedRecord(int unitId) {
        return presentedRecord.unitId() == unitId ? presentedRecord
              : presentedOwnRecord.unitId() == unitId ? presentedOwnRecord : GpuUnitRecord.Snapshot.EMPTY;
    }

    /**
     * Applies the newest status. The units are presented from the last status captured while the playback was idle
     * (C.6); a closed log opens itself for the report phases after initiative and closes again with the next phase
     * that is no report, while a log the player opened stays open (A.10 J1); the focus unit follows C.5.
     *
     * @return the unit the HUD selects once because the local turn began with the focus able to act, or
     *       {@code Entity.NONE}
     */
    int update(GpuBattleStatus.Snapshot next, GpuUnitRecord.Snapshot nextRecord, boolean busy) {
        return update(next, nextRecord, nextRecord, busy);
    }

    private int update(GpuBattleStatus.Snapshot next, GpuUnitRecord.Snapshot nextRecord,
          GpuUnitRecord.Snapshot nextOwnRecord, boolean busy) {
        GpuBattleStatus.UnitStatus inspection = presented(inspected);
        if (inspection != null && inspection.side() == GpuBattleStatus.Side.OWN) {
            ownInspection = inspected;
        }
        if (next == status && nextRecord == record && nextOwnRecord == ownRecord && busy == playbackBusy
              && (nextOwnRecord.unitId() != ownCardUnit()
                    || nextOwnRecord.unitId() == presentedOwnRecord.unitId())
              && (nextRecord.unitId() != cardUnit() || nextRecord.unitId() == presentedRecord.unitId())) {
            return Entity.NONE;
        }
        GpuBattleStatus.Snapshot previous = focusStatus == null ? GpuBattleStatus.Snapshot.EMPTY : focusStatus;
        status = next;
        record = nextRecord;
        ownRecord = nextOwnRecord;
        playbackBusy = busy;
        if (!busy || presentedUnits.isEmpty()) {
            presentedUnits = next.units();
            presentedRecord = nextRecord;
            presentedOwnRecord = nextOwnRecord;
        } else if (nextRecord.unitId() != presentedRecord.unitId() && nextRecord.unitId() == cardUnit()) {
            presentedRecord = nextRecord;
        }
        if (nextOwnRecord.unitId() != presentedOwnRecord.unitId() && nextOwnRecord.unitId() == ownCardUnit()) {
            presentedOwnRecord = nextOwnRecord;
        }
        if (busy && focusStatus != null) {
            return Entity.NONE;
        }
        focusStatus = next;
        boolean phaseStart = next.round() != previous.round() || next.phase() != previous.phase();
        if (phaseStart) {
            boolean autoLog = next.phase().isReport() && !next.phase().isInitiativeReport();
            if (autoLog && !logOpen) {
                logOpen = true;
                logAuto = true;
            } else if (!autoLog && logAuto) {
                logOpen = false;
                logAuto = false;
            }
        }
        return updateFocus(previous, next, phaseStart);
    }

    /** Snapshot the unit context only at the queue's idle boundary, never at an intermediate action's contact. */
    int update(GpuBoardSource.Frame frame, boolean busy) {
        if (selectionFrame != null && selectionFrame.boardGeneration() != frame.boardGeneration()) {
            selectionFrame = null;
            focusStatus = null;
            status = null;
            ownInspection = Entity.NONE;
            presentedUnits = List.of();
            presentedRecord = GpuUnitRecord.Snapshot.EMPTY;
            presentedOwnRecord = GpuUnitRecord.Snapshot.EMPTY;
        }
        if (!busy || selectionFrame == null) {
            selectionFrame = frame;
            concealedDuringPlayback = false;
        }
        int select = update(frame.status(), frame.panels().record(), frame.panels().ownRecord(), busy);
        for (BoardScene.Animation event : frame.timeline()) {
            if (busy && event instanceof BoardScene.Concealed hidden && frame.scene() != null
                  && hidden.boardId() == frame.scene().boardId()) {
                // Visibility changes revoke retained identities immediately, even while other actions remain queued.
                concealedDuringPlayback = true;
                presentedUnits = presentedUnits.stream().filter(unit -> unit.id() != hidden.entityId()).toList();
                if (presentedRecord.unitId() == hidden.entityId()) { presentedRecord = GpuUnitRecord.Snapshot.EMPTY; }
                if (presentedOwnRecord.unitId() == hidden.entityId()) {
                    presentedOwnRecord = GpuUnitRecord.Snapshot.EMPTY;
                }
                if (focus == hidden.entityId()) { focus = Entity.NONE; }
                if (ownInspection == hidden.entityId()) { ownInspection = Entity.NONE; }
                if (inspected == hidden.entityId()) { inspected = Entity.NONE; }
                GpuBattleStatus.Snapshot held = focusStatus;
                focusStatus = new GpuBattleStatus.Snapshot(held.round(), held.phase(), held.myTurn(), held.localPlayerId(),
                      held.actorId() == hidden.entityId() ? Entity.NONE : held.actorId(), held.turns(), held.turnIndex(),
                      presentedUnits, held.initiative(), held.turnOrderHidden(),
                      held.links().stream().filter(link -> link.fromId() != hidden.entityId()
                            && link.toId() != hidden.entityId()).toList(), GpuBattleStatus.Arc.NONE);
            }
        }
        return select;
    }

    /**
     * C.5: candidates are the own pending units in ascending id order, the order the phase's "next" command walks. A
     * phase starts at the first, or at the player's pick while it is a candidate; a finished local turn, or a focus
     * that stops being a candidate without a pick holding it, moves to the next candidate after it; during the local
     * turn the focus is the actor. The actor's commit reaches the status before its turn ends, so the focus waits for
     * the turn's end and moves on once.
     */
    private int updateFocus(GpuBattleStatus.Snapshot previous, GpuBattleStatus.Snapshot next, boolean phaseStart) {
        List<Integer> candidates = next.units().stream()
              .filter(unit -> unit.side() == GpuBattleStatus.Side.OWN && !unit.sensorContact() && unit.pending())
              .map(GpuBattleStatus.UnitStatus::id).sorted().toList();
        int before = focus;
        boolean newTurn = next.turnIndex() != previous.turnIndex();
        boolean turnBegins = next.myTurn() && (phaseStart || !previous.myTurn() || newTurn);
        boolean turnEnded = !phaseStart && previous.myTurn() && (!next.myTurn() || newTurn);
        if (phaseStart && !(picked && candidates.contains(focus))) {
            focus = candidates.isEmpty() ? Entity.NONE : candidates.get(0);
        } else if (turnEnded) {
            focus = after(candidates, focus);
        } else if (!turnBegins && next.myTurn() && next.actorId() != previous.actorId()
              && own(next, next.actorId())) {
            // A selection during the local turn; the turn's first actor is the phase display's choice (below).
            focus = next.actorId();
        }
        // The player's pick holds until one of these events, or until the unit is no own unit any more.
        picked &= !phaseStart && !turnBegins && !turnEnded && own(next, focus);
        boolean duringTurn = next.myTurn() && !turnBegins;
        if (!duringTurn && !picked && !candidates.contains(focus)) {
            focus = after(candidates, focus);
        }
        int select = Entity.NONE;
        if (turnBegins) {
            GpuBattleStatus.UnitStatus unit = unit(next, focus);
            GamePhase phase = next.phase();
            if (unit != null && unit.canActNow() && (phase.isMovement() || phase.isFiring() || phase.isPhysical())) {
                // Overrides the phase display's own choice at the start of its turn.
                select = focus == next.actorId() ? Entity.NONE : focus;
            } else if (own(next, next.actorId())) {
                focus = next.actorId();
            }
        }
        // A cleared selection ends with the next turn, phase or focus unit.
        if (focus != before || phaseStart || turnBegins || turnEnded) { ownInspection = Entity.NONE; }
        selectionCleared &= !phaseStart && !turnBegins && !turnEnded && focus == before;
        return select;
    }

    /** The first candidate after {@code id}, wrapping around, or {@code Entity.NONE} without candidates. */
    private static int after(List<Integer> candidates, int id) {
        return candidates.stream().filter(candidate -> candidate > id).findFirst()
              .orElse(candidates.isEmpty() ? Entity.NONE : candidates.get(0));
    }

    /** The status of unit {@code id}, or null. */
    static GpuBattleStatus.UnitStatus unit(GpuBattleStatus.Snapshot status, int id) {
        return status.units().stream().filter(unit -> unit.id() == id).findFirst().orElse(null);
    }

    private static boolean own(GpuBattleStatus.Snapshot status, int id) {
        GpuBattleStatus.UnitStatus unit = unit(status, id);
        return unit != null && unit.side() == GpuBattleStatus.Side.OWN;
    }
}
