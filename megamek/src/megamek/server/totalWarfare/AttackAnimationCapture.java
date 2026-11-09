/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.server.totalWarfare;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import megamek.common.HitData;
import megamek.common.NuclearDetonation;
import megamek.common.ResolvedAttack;
import megamek.common.board.BoardLocation;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.units.Entity;

/** One synchronous rules resolution. Complete visual metadata before sending, preserving the original packet order. */
final class AttackAnimationCapture {
    private record Delivery(Integer connection, Packet packet) { }
    private final List<Delivery> deliveries = new ArrayList<>();
    private final List<ResolvedAttack> attacks = new ArrayList<>();
    private final Map<UUID, List<ResolvedAttack.Impact>> impacts = new HashMap<>();
    private final Map<UUID, List<BoardLocation>> collapses = new HashMap<>();

    private record Bracket(NuclearDetonation detonation, Set<BoardLocation> collapses) { }
    /** The detonations being resolved, innermost first: what comes down while one is open is its doing. */
    private final Deque<Bracket> open = new ArrayDeque<>();
    /** Each closed detonation's collapses, by id, for its completion packet. */
    private final Map<UUID, List<BoardLocation>> named = new HashMap<>();
    /** Explosions never sent: folded into what set them off, or bringing nothing down. */
    private final Set<UUID> unsent = new HashSet<>();

    void packet(Integer connection, Packet packet) {
        if (detonation(packet) instanceof NuclearDetonation detonation) {
            if (completes(packet)) { close(detonation); } else { open(detonation); }
        }
        deliveries.add(new Delivery(connection, packet));
    }

    private static NuclearDetonation detonation(Packet packet) {
        return packet.command() == PacketCommand.NUCLEAR_DETONATION && packet.getObject(0) instanceof NuclearDetonation
              detonation ? detonation : null;
    }

    /** Whether a detonation's packet is its completion rather than its start. */
    private static boolean completes(Packet packet) {
        return Boolean.TRUE.equals(packet.getObject(1));
    }

    /**
     * A detonation starts. An explosion a strike's front or a nuclear round sets off is theirs, and one going off again
     * where an open explosion did (a multi-hex fuel tank's next hex) is that one: what it brings down falls with them,
     * and it is never sent. Any other opens a bracket of its own.
     */
    private void open(NuclearDetonation detonation) {
        var outer = open.peek();
        boolean folded = detonation.kind() == NuclearDetonation.Kind.EXPLOSION && (outer == null ? nuclearRoundResolving()
              : outer.detonation().kind() != NuclearDetonation.Kind.EXPLOSION || open.stream().anyMatch(bracket ->
                    bracket.detonation().coords().equals(detonation.coords())
                          && bracket.detonation().boardId() == detonation.boardId()));
        if (folded) { unsent.add(detonation.id()); } else { open.push(new Bracket(detonation, new LinkedHashSet<>())); }
    }

    /** A detonation completes: its bracket names what it brought down; an explosion that brought nothing down is not sent. */
    private void close(NuclearDetonation detonation) {
        if (open.isEmpty() || !open.peek().detonation().id().equals(detonation.id())) { return; }
        var bracket = open.pop();
        named.put(detonation.id(), List.copyOf(bracket.collapses()));
        if (detonation.kind() == NuclearDetonation.Kind.EXPLOSION && bracket.collapses().isEmpty()) {
            unsent.add(detonation.id());
        }
    }

    /** Whether the newest attack being resolved, past the deaths it caused, is a nuclear round's. */
    private boolean nuclearRoundResolving() {
        for (int index = attacks.size() - 1; index >= 0; index--) {
            var attack = attacks.get(index);
            if (attack.kind() == ResolvedAttack.Kind.DEATH) { continue; }
            return attack.shot() != null && attack.shot().nuclearType() != null;
        }
        return false;
    }

    void attack(ResolvedAttack result, List<Integer> recipients) {
        attacks.add(result);
        for (int recipient : recipients) { packet(recipient, new Packet(PacketCommand.ENTITY_ATTACK_RESOLVED, result)); }
    }

    void hit(Entity target, HitData hit, int damage) {
        // A later fall is not another projectile. Only this visual list is filtered; damage has its own rules path.
        if (damage <= 0 || hit.isFallDamage() || hit.getLocation() < 0 || hit.getLocation() >= target.locations()) { return; }
        for (int index = attacks.size() - 1; index >= 0; index--) {
            var attack = attacks.get(index);
            if (attack.kind() == ResolvedAttack.Kind.DEATH || attack.target().entityId() != target.getId()
                  || hit.getAttackerId() != Entity.NONE && hit.getAttackerId() != attack.attacker().entityId()) { continue; }
            if (!attack.hit()) { return; }
            impacts.computeIfAbsent(attack.id(), ignored -> new ArrayList<>())
                  .add(new ResolvedAttack.Impact(target.getLocationAbbr(hit.getLocation()), hit.isRear(), damage));
            return;
        }
    }

    /**
     * The construction factor of the structure hex {@code at} went to zero: the rules bring it down at the phase's end,
     * which its collapse plays as the doing of the innermost detonation being resolved (a scheduled strike, an orbital
     * bombardment, an explosion), whose completion names it, so it falls with its front. Outside one, the attack being
     * resolved takes it when it hit, or for artillery when its shell landed and went off, even scattered; a miss or a
     * dud takes nothing.
     */
    void collapse(BoardLocation at) {
        if (!open.isEmpty()) {
            open.peek().collapses().add(at);
            return;
        }
        for (int index = attacks.size() - 1; index >= 0; index--) {
            var attack = attacks.get(index);
            if (attack.kind() == ResolvedAttack.Kind.DEATH) { continue; }
            var shot = attack.shot();
            boolean landed = shot != null && shot.artillery() && shot.impact() != null && shot.damagePerHit() > 0;
            if (attack.hit() || landed) { collapses.computeIfAbsent(attack.id(), ignored -> new ArrayList<>()).add(at); }
            return;
        }
    }

    void flush(Consumer<Packet> broadcast, BiConsumer<Integer, Packet> targeted) {
        for (var delivery : deliveries) {
            Packet packet = delivery.packet();
            if (packet.command() == PacketCommand.ENTITY_ATTACK_RESOLVED && packet.getObject(0) instanceof ResolvedAttack attack) {
                packet = new Packet(packet.command(), attack.withImpacts(impacts.getOrDefault(attack.id(), attack.impacts()))
                      .withCollapses(collapses.getOrDefault(attack.id(), attack.collapses())));
            } else if (detonation(packet) instanceof NuclearDetonation detonation) {
                if (unsent.contains(detonation.id())) { continue; }
                if (completes(packet) && named.containsKey(detonation.id())) {
                    packet = new Packet(packet.command(), detonation.withCollapses(named.get(detonation.id())), true);
                }
            }
            if (delivery.connection() == null) { broadcast.accept(packet); }
            else { targeted.accept(delivery.connection(), packet); }
        }
    }
}
