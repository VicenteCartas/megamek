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
package megamek.common.game;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

import megamek.common.board.Coords;

/**
 * The orders a Princess bot is following that are not part of its behavior settings, sent through
 * {@link megamek.common.net.enums.PacketCommand#PRINCESS_STATUS} after every change so that allied players can see
 * them. The settings themselves travel through {@link megamek.common.net.enums.PacketCommand#PRINCESS_SETTINGS}.
 *
 * <p>Sent over the network only; it is never part of a savegame. The sets are copied into {@link HashSet}s because the
 * network filter ({@link megamek.common.net.marshalling.SanityInputFilter}) rejects the JDK's immutable sets.</p>
 *
 * @param holdingPosition {@code true} while the bot's units hold their position
 * @param tagTargets      the enemy units the bot has been asked to TAG
 * @param strategicHexes  the hexes the bot moves to and attacks
 */
public record BotStatus(boolean holdingPosition, Set<Integer> tagTargets, Set<Coords> strategicHexes)
      implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The status of a bot that has not reported: no standing orders. */
    public static final BotStatus NONE = new BotStatus(false, Set.of(), Set.of());

    public BotStatus {
        tagTargets = new HashSet<>(tagTargets);
        strategicHexes = new HashSet<>(strategicHexes);
    }
}
