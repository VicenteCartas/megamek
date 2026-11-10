/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Nullable;
import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.units.Entity;

/**
 * The Nova CEWS networks as they stand, read only, as the Nova network view shows them: the local player's Nova units
 * and those of allied players (who may link with them, IO: AE p.60), each with the units it is linked with. A unit is
 * linked when another Nova unit shares its network; a network holds at most {@link #MAX_NETWORK} units.
 */
public final class NovaNetworkForm {
    /** The most units one Nova CEWS network holds (IO: AE p.60). */
    public static final int MAX_NETWORK = 3;

    /** A unit the unit is linked with. */
    public record Partner(int id, String name) { }

    /**
     * One Nova unit.
     *
     * @param owner    the name of the allied player who commands it, or null for the local player's units
     * @param partners the other Nova units on its network, empty when it is not linked
     */
    public record Unit(int id, String name, @Nullable String owner, List<Partner> partners) {
        public Unit {
            partners = List.copyOf(partners);
        }

        /** Whether another Nova unit shares its network. */
        public boolean linked() {
            return !partners.isEmpty();
        }

        /** How many units its network holds, itself included. */
        public int networkSize() {
            return partners.size() + 1;
        }

        /** Whether its network has no room for another unit. */
        public boolean full() {
            return networkSize() >= MAX_NETWORK;
        }
    }

    private final List<Unit> yours;
    private final List<Unit> allied;

    private NovaNetworkForm(List<Unit> yours, List<Unit> allied) {
        this.yours = List.copyOf(yours);
        this.allied = List.copyOf(allied);
    }

    /** Reads the Nova units of {@code localPlayerId} and their allies from {@code game}. */
    public static NovaNetworkForm of(Game game, int localPlayerId) {
        Player local = game.getPlayer(localPlayerId);
        List<Entity> novas = game.getEntitiesVector().stream().filter(Entity::hasNovaCEWS).toList();
        List<Unit> yours = new ArrayList<>();
        List<Unit> allied = new ArrayList<>();
        for (Entity entity : novas) {
            if (entity.getOwnerId() == localPlayerId) {
                yours.add(unit(entity, null, novas));
            } else if (entity.getOwner() != null && local != null && !entity.getOwner().isEnemyOf(local)) {
                allied.add(unit(entity, entity.getOwner().getName(), novas));
            }
        }
        return new NovaNetworkForm(yours, allied);
    }

    private static Unit unit(Entity entity, @Nullable String owner, List<Entity> novas) {
        String network = entity.getC3NetId();
        List<Partner> partners = new ArrayList<>();
        if (network != null) {
            for (Entity other : novas) {
                if (other.getId() != entity.getId() && network.equals(other.getC3NetId())) {
                    partners.add(new Partner(other.getId(), other.getShortName()));
                }
            }
        }
        return new Unit(entity.getId(), entity.getShortName(), owner, partners);
    }

    /** The local player's Nova units. */
    public List<Unit> yours() {
        return yours;
    }

    /** The allied players' Nova units. */
    public List<Unit> allied() {
        return allied;
    }

    /** Whether there is no Nova unit to show. */
    public boolean isEmpty() {
        return yours.isEmpty() && allied.isEmpty();
    }
}
