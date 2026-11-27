/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.bot.princess;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;

import megamek.common.board.Coords;

/**
 * One change to a Princess bot's behavior, sent by an allied player or a game master through
 * {@link megamek.common.net.enums.PacketCommand#BOT_BEHAVIOR_CHANGE}. Each change carries only the value that changed,
 * so a player whose copy of the settings is out of date cannot undo what another player or the bot itself changed.
 *
 * <p>The bot applies the change to its live settings and state, then reports its settings back to every client. The
 * chat commands apply theirs through the same changes, so both ways of commanding a bot behave alike.</p>
 */
public sealed interface BehaviorChange extends Serializable {

    /**
     * Makes this change on the bot's live settings and state. Does not report the result; the caller does, once for
     * all the changes of a message.
     *
     * @param princess the bot to change
     */
    void apply(Princess princess);

    /** The 0 to 10 behavior indices a player can set directly. */
    enum Trait {
        AGGRESSION(BehaviorSettings::getHyperAggressionIndex, BehaviorSettings::setHyperAggressionIndex),
        BRAVERY(BehaviorSettings::getBraveryIndex, BehaviorSettings::setBraveryIndex),
        SELF_PRESERVATION(BehaviorSettings::getSelfPreservationIndex, BehaviorSettings::setSelfPreservationIndex),
        FALL_SHAME(BehaviorSettings::getFallShameIndex, BehaviorSettings::setFallShameIndex),
        MUTUAL_SUPPORT(BehaviorSettings::getMutualSupportIndex, BehaviorSettings::setMutualSupportIndex);

        private final ToIntFunction<BehaviorSettings> read;
        private final ObjIntConsumer<BehaviorSettings> write;

        Trait(ToIntFunction<BehaviorSettings> read, ObjIntConsumer<BehaviorSettings> write) {
            this.read = read;
            this.write = write;
        }

        /** Its index in {@code settings}. */
        public int of(BehaviorSettings settings) {
            return read.applyAsInt(settings);
        }

        void set(BehaviorSettings settings, int index) {
            write.accept(settings, index);
        }
    }

    /** Sets the combat posture. */
    record Posture(CombatPosture posture) implements BehaviorChange {
        @Serial
        private static final long serialVersionUID = 1L;

        public Posture {
            Objects.requireNonNull(posture);
        }

        @Override
        public void apply(Princess princess) {
            princess.getBehaviorSettings().setCombatPosture(posture);
        }
    }

    /** Sets one behavior index; values outside 0 to 10 are pulled back into range. */
    record Index(Trait trait, int value) implements BehaviorChange {
        @Serial
        private static final long serialVersionUID = 1L;

        public Index {
            Objects.requireNonNull(trait);
        }

        @Override
        public void apply(Princess princess) {
            trait.set(princess.getBehaviorSettings(), value);
        }
    }

    /**
     * Takes a personality: the behavior values of {@code preset}, which the sender resolves so the bot does not need
     * the same preset files. Keeps the combat posture, targets, ignored units and retreat.
     */
    record Style(BehaviorSettings preset) implements BehaviorChange {
        @Serial
        private static final long serialVersionUID = 1L;

        public Style {
            Objects.requireNonNull(preset);
        }

        @Override
        public void apply(Princess princess) {
            princess.takeStyle(preset);
        }
    }

    /**
     * Sends every unit off the board by {@code edge}, or calls the retreat off with {@link CardinalEdge#NONE}. A bot
     * that follows unit orders gives each unit an exit order instead, so it shows on the map.
     */
    record Retreat(CardinalEdge edge) implements BehaviorChange {
        @Serial
        private static final long serialVersionUID = 1L;

        public Retreat {
            Objects.requireNonNull(edge);
        }

        @Override
        public void apply(Princess princess) {
            boolean leaving = edge != CardinalEdge.NONE;
            String reason = leaving ? "Received flee order - " + edge.name() : "Received cancel flee order";
            princess.getBehaviorSettings().setDestinationEdge(edge);
            // Auto-flee plus a destination edge is what actually makes healthy units move toward the edge
            // (see UnitBehavior#calculateUnitBehavior), and the flee-board flag allows them to leave the map.
            princess.getBehaviorSettings().setAutoFlee(leaving);
            princess.setFallBack(leaving, reason);
            princess.setFleeBoard(leaving, reason);
            if (princess.followsUnitOrders()) {
                if (leaving) {
                    princess.getUnitOrdersFollower().orderAllToExit(edge);
                } else {
                    princess.getUnitOrdersFollower().cancelExitOrders();
                }
            }
        }
    }

    /**
     * Puts an enemy unit on the priority list with a target priority from 1 (shoot first) to 5, or takes it off with
     * {@link #REMOVE}.
     */
    record PriorityTarget(int unitId, int priority) implements BehaviorChange {
        @Serial
        private static final long serialVersionUID = 1L;

        /** The priority that takes the unit off the list. */
        public static final int REMOVE = 0;

        @Override
        public void apply(Princess princess) {
            if (priority == REMOVE) {
                princess.getBehaviorSettings().removePriorityUnit(unitId);
            } else {
                princess.getBehaviorSettings().setPriorityUnit(unitId, priority);
            }
        }
    }

    /** Adds or removes a hex the bot moves to and attacks. Hexes off the board are skipped. */
    record StrategicHex(Coords hex, boolean add) implements BehaviorChange {
        @Serial
        private static final long serialVersionUID = 1L;

        public StrategicHex {
            Objects.requireNonNull(hex);
        }

        @Override
        public void apply(Princess princess) {
            if (add) {
                princess.addStrategicBuildingTarget(hex);
            } else {
                princess.removeStrategicBuildingTarget(hex);
            }
        }
    }

    /** Adds units to the list of units the bot ignores completely, or takes them off it. */
    record Ignore(Set<Integer> unitIds, boolean add) implements BehaviorChange {
        @Serial
        private static final long serialVersionUID = 1L;

        public Ignore {
            // a HashSet, as the network filter rejects the JDK's immutable sets
            unitIds = new HashSet<>(unitIds);
        }

        @Override
        public void apply(Princess princess) {
            for (int unitId : unitIds) {
                if (add) {
                    princess.getBehaviorSettings().addIgnoredUnitTarget(unitId);
                } else {
                    princess.getBehaviorSettings().removeIgnoredUnitTarget(unitId);
                }
            }
        }
    }

    /** Empties the list of ignored units. */
    record ClearIgnored() implements BehaviorChange {
        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public void apply(Princess princess) {
            princess.getBehaviorSettings().clearIgnoredUnitTargets();
        }
    }
}
