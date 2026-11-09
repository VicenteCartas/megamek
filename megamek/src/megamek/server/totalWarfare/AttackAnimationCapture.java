/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.server.totalWarfare;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import megamek.common.HitData;
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
    /** How deep the resolution is in consequences whose collapses no attack takes ({@link #uncredited}). */
    private int consequenceDepth;

    void packet(Integer connection, Packet packet) { deliveries.add(new Delivery(connection, packet)); }

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
     * The attack being resolved took the construction factor of the structure hex {@code at} to zero: the rules bring it
     * down at the phase's end, which its collapse plays as this attack's doing. It takes it when it hit, or for artillery
     * when its shell landed and went off, even scattered; a miss, a dud or what a consequence brings down
     * ({@link #uncredited}) is no attack's doing.
     */
    void collapse(BoardLocation at) {
        if (consequenceDepth > 0) { return; }
        for (int index = attacks.size() - 1; index >= 0; index--) {
            var attack = attacks.get(index);
            if (attack.kind() == ResolvedAttack.Kind.DEATH) { continue; }
            var shot = attack.shot();
            boolean landed = shot != null && shot.artillery() && shot.impact() != null && shot.damagePerHit() > 0;
            if (attack.hit() || landed) { collapses.computeIfAbsent(attack.id(), ignored -> new ArrayList<>()).add(at); }
            return;
        }
    }

    /** Runs {@code consequence}, a fuel tank's explosion, crediting nothing it brings down to the attack. */
    void uncredited(Runnable consequence) {
        consequenceDepth++;
        try {
            consequence.run();
        } finally {
            consequenceDepth--;
        }
    }

    void flush(Consumer<Packet> broadcast, BiConsumer<Integer, Packet> targeted) {
        for (var delivery : deliveries) {
            Packet packet = delivery.packet();
            if (packet.command() == PacketCommand.ENTITY_ATTACK_RESOLVED && packet.getObject(0) instanceof ResolvedAttack attack) {
                packet = new Packet(packet.command(), attack.withImpacts(impacts.getOrDefault(attack.id(), attack.impacts()))
                      .withCollapses(collapses.getOrDefault(attack.id(), attack.collapses())));
            }
            if (delivery.connection() == null) { broadcast.accept(packet); }
            else { targeted.accept(delivery.connection(), packet); }
        }
    }
}
