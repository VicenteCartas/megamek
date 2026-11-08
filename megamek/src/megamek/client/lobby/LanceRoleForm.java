/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.common.OffBoardDirection;
import megamek.common.orders.LanceRole;
import megamek.common.orders.LanceRoles;

/**
 * A bot lance's role as the lobby's Role card sets it: none, a convoy that leaves by an edge or waits at its route's
 * end, or an escort round another lance with its settings. The rules and the texts are the Move Order editor's Role
 * tab's, so a role reads the same wherever it is set; escorts always keep in step with their convoy.
 * <p>
 * Swing thread.
 */
public final class LanceRoleForm {
    /** The role choices: none, or one of the kinds. */
    public enum Choice {
        NONE,
        CONVOY,
        ESCORT
    }

    /** The exit edges a convoy can leave by, in the card's order. */
    public static final List<OffBoardDirection> EDGES = List.of(OffBoardDirection.NORTH, OffBoardDirection.EAST,
          OffBoardDirection.SOUTH, OffBoardDirection.WEST);

    private final String lanceName;
    private final List<LanceRoles.ConvoyChoice> convoys;
    private Choice choice = Choice.NONE;
    private OffBoardDirection exitEdge;
    private boolean waits;
    private int convoy;
    private final Set<LanceRole.Position> positions = EnumSet.noneOf(LanceRole.Position.class);
    private LanceRole.Distance distance;
    private LanceRole.Contact contact;
    private LanceRole.LeaveToFight leave;
    private LanceRole.WhenConvoyGone gone;

    /**
     * @param lanceName       the lance's name, for the summary
     * @param convoys         the lances on the side the escort can guard
     * @param defaultExitEdge the exit edge a new convoy starts with
     * @param shown           the role to show first, or {@code null} for none
     */
    public LanceRoleForm(String lanceName, List<LanceRoles.ConvoyChoice> convoys, OffBoardDirection defaultExitEdge,
          @Nullable LanceRole shown) {
        this.lanceName = Objects.requireNonNull(lanceName);
        this.convoys = List.copyOf(convoys);
        exitEdge = EDGES.contains(defaultExitEdge) ? defaultExitEdge : OffBoardDirection.NORTH;
        loadEscort(LanceRole.defaultEscort(-1));
        if (shown == null) {
            return;
        }
        if (shown.isConvoy()) {
            choice = Choice.CONVOY;
            if (EDGES.contains(shown.getExitEdge())) {
                exitEdge = shown.getExitEdge();
            }
            waits = shown.isWaitingAtRouteEnd();
        } else if (shown.isEscort()) {
            choice = Choice.ESCORT;
            loadEscort(shown);
        }
    }

    private void loadEscort(LanceRole escort) {
        positions.clear();
        positions.addAll(escort.getPositions());
        distance = escort.getDistance();
        contact = escort.getContact();
        leave = escort.getLeaveToFight();
        gone = escort.getWhenConvoyGone();
        for (int index = 0; index < convoys.size(); index++) {
            if (convoys.get(index).forceId() == escort.getConvoyForceId()) {
                convoy = index;
            }
        }
    }

    public String lanceName() {
        return lanceName;
    }

    public Choice choice() {
        return choice;
    }

    public void choice(Choice choice) {
        this.choice = Objects.requireNonNull(choice);
    }

    // ---- The convoy

    public OffBoardDirection exitEdge() {
        return exitEdge;
    }

    public void exitEdge(OffBoardDirection edge) {
        if (EDGES.contains(edge)) {
            exitEdge = edge;
        }
    }

    /** Whether the convoy waits at its route's end rather than leaving the board. */
    public boolean waits() {
        return waits;
    }

    public void waits(boolean waits) {
        this.waits = waits;
    }

    /** Whether the exit edge matters: only to a convoy that leaves. */
    public boolean edgeApplies() {
        return !waits;
    }

    /** What the convoy does, in a sentence. */
    public String convoyHelp() {
        return Messages.getString(waits ? "BotCommandPanel.Role.convoy.helpWait" : "BotCommandPanel.Role.convoy.help",
              edgeName(exitEdge));
    }

    // ---- The escort

    /** The lances the escort can guard; empty when there is none. */
    public List<LanceRoles.ConvoyChoice> convoys() {
        return convoys;
    }

    /** The index of the guarded lance in {@link #convoys()}. */
    public int convoy() {
        return convoy;
    }

    public void convoy(int index) {
        if (index >= 0 && index < convoys.size()) {
            convoy = index;
        }
    }

    private @Nullable LanceRoles.ConvoyChoice chosenConvoy() {
        return convoys.isEmpty() ? null : convoys.get(convoy);
    }

    /** A guarded lance as the choice reads: a convoy, or a lance that becomes one. */
    public static String convoyLabel(LanceRoles.ConvoyChoice choice) {
        return Messages.getString(choice.isConvoy() ? "BotCommandPanel.Role.escort.convoyChoice"
              : "BotCommandPanel.Role.escort.lanceChoice", choice.name(), choice.unitCount());
    }

    public boolean holds(LanceRole.Position position) {
        return positions.contains(position);
    }

    public void toggle(LanceRole.Position position) {
        if (!positions.remove(position)) {
            positions.add(position);
        }
    }

    /** Takes every place round the convoy. */
    public void surround() {
        positions.addAll(EnumSet.allOf(LanceRole.Position.class));
    }

    public LanceRole.Distance distance() {
        return distance;
    }

    public void distance(LanceRole.Distance distance) {
        this.distance = Objects.requireNonNull(distance);
    }

    public LanceRole.Contact contact() {
        return contact;
    }

    public void contact(LanceRole.Contact contact) {
        this.contact = Objects.requireNonNull(contact);
    }

    public LanceRole.LeaveToFight leave() {
        return leave;
    }

    public void leave(LanceRole.LeaveToFight leave) {
        this.leave = Objects.requireNonNull(leave);
    }

    /** Whether how far it leaves to fight matters: only when it breaks off to fight. */
    public boolean leaveApplies() {
        return contact == LanceRole.Contact.BREAK_AND_FIGHT;
    }

    public LanceRole.WhenConvoyGone gone() {
        return gone;
    }

    public void gone(LanceRole.WhenConvoyGone gone) {
        this.gone = Objects.requireNonNull(gone);
    }

    public String distanceHint() {
        return hint("distance." + distance.name());
    }

    public String contactHint() {
        return hint("contact." + contact.name());
    }

    public String leaveHint() {
        return leaveApplies() ? hint("leave." + leave.name())
              : Messages.getString("BotCommandPanel.Role.hint.leave.onlyBreaking");
    }

    public String goneHint() {
        return hint("gone." + gone.name());
    }

    private static String hint(String keySuffix) {
        return Messages.getString("BotCommandPanel.Role.hint." + keySuffix, LanceRole.BRIEF_CHASE_HEXES);
    }

    /** What the escort does, or what it still lacks. */
    public String escortSummary() {
        if (positions.isEmpty()) {
            return Messages.getString("BotCommandPanel.Role.summary.noPositions");
        }
        LanceRoles.ConvoyChoice guarded = chosenConvoy();
        if (guarded == null) {
            return Messages.getString("BotCommandPanel.Role.escort.noConvoy");
        }
        List<String> places = new ArrayList<>();
        for (LanceRole.Position position : LanceRole.Position.values()) {
            if (positions.contains(position)) {
                places.add(positionName(position));
            }
        }
        return lanceName + ": "
              + Messages.getString("BotCommandPanel.Role.summary.escort", distance.getNearest(),
              distance.getFurthest(), guarded.name(), String.join(", ", places),
              Messages.getString("BotCommandPanel.Role.summary.movement." + LanceRole.Movement.IN_STEP.name())) + ' '
              + Messages.getString("BotCommandPanel.Role.summary.contact." + contact.name()) + ' '
              + Messages.getString("BotCommandPanel.Role.summary.leave." + leave.name(), LanceRole.BRIEF_CHASE_HEXES)
              + ' ' + Messages.getString("BotCommandPanel.Role.summary.gone." + gone.name())
              + (guarded.isConvoy() ? "" : ' ' + Messages.getString("BotCommandPanel.Role.summary.becomesConvoy",
              guarded.name(), edgeName(guarded.newExitEdge())));
    }

    // ---- The answer

    /** Whether the role can be sent: none, a convoy, or an escort with a lance to guard and a place round it. */
    public boolean complete() {
        return choice != Choice.ESCORT || (chosenConvoy() != null && !positions.isEmpty());
    }

    /** The role as set, or {@code null} for none and for an escort not yet {@link #complete()}. */
    public @Nullable LanceRole role() {
        return switch (choice) {
            case NONE -> null;
            case CONVOY -> LanceRole.convoy(exitEdge, waits);
            case ESCORT -> complete() ? LanceRole.escort(chosenConvoy().forceId(), EnumSet.copyOf(positions),
                  distance, LanceRole.Movement.IN_STEP, contact, leave, gone) : null;
        };
    }

    /** The lance the escort guards when it is not a convoy yet, and so becomes one once the role is sent. */
    public Optional<LanceRoles.ConvoyChoice> lanceToMakeConvoy() {
        LanceRoles.ConvoyChoice guarded = chosenConvoy();
        if (choice != Choice.ESCORT || guarded == null || guarded.isConvoy()) {
            return Optional.empty();
        }
        return Optional.of(guarded);
    }

    // ---- Names

    public static String choiceName(Choice choice) {
        return Messages.getString("BotCommandPanel.Role." + choice.name());
    }

    public static String edgeName(OffBoardDirection edge) {
        return Messages.getString("BotCommandPanel.Role.edge." + edge.name());
    }

    public static String positionName(LanceRole.Position position) {
        return Messages.getString("BotCommandPanel.Role.position." + position.name());
    }

    public static String distanceName(LanceRole.Distance distance) {
        return Messages.getString("BotCommandPanel.Role.distance." + distance.name());
    }

    public static String contactName(LanceRole.Contact contact) {
        return Messages.getString("BotCommandPanel.Role.contact." + contact.name());
    }

    public static String leaveName(LanceRole.LeaveToFight leave) {
        return Messages.getString("BotCommandPanel.Role.leave." + leave.name());
    }

    public static String goneName(LanceRole.WhenConvoyGone gone) {
        return Messages.getString("BotCommandPanel.Role.gone." + gone.name());
    }
}
