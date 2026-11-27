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

import megamek.common.Player;

/**
 * A standing order to a Princess bot that is not part of its behavior settings, sent by an allied player or a game
 * master through {@link megamek.common.net.enums.PacketCommand#BOT_ORDER}. The bot follows it, then reports its state
 * to every client: hold and TAG through {@link megamek.common.game.BotStatus}, blood feuds through its honor report.
 * The chat commands give the same orders through these records.
 */
public sealed interface BotOrder extends Serializable {

    /**
     * Follows this order. Does not report the result; the caller does.
     *
     * @param princess the bot to order
     */
    void apply(Princess princess);

    /** Holds every unit in place, or lets them move again. */
    record Hold(boolean hold) implements BotOrder {
        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public void apply(Princess princess) {
            princess.setHoldPosition(hold);
        }
    }

    /** Asks the bot to put its TAG on an enemy unit, for a player's homing artillery. */
    record TagTarget(int unitId) implements BotOrder {
        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public void apply(Princess princess) {
            princess.addDesignatedTagTarget(unitId);
        }
    }

    /** Returns the bot's TAG to choosing its own targets. */
    record ClearTagTargets() implements BotOrder {
        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public void apply(Princess princess) {
            princess.clearDesignatedTagTargets();
        }
    }

    /** Declares a blood feud: the bot no longer shows the player's units any mercy. Unknown players are skipped. */
    record BloodFeud(int playerId) implements BotOrder {
        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public void apply(Princess princess) {
            Player player = princess.getGame().getPlayer(playerId);
            if (player != null) {
                princess.getHonorUtil().setEnemyDishonored(playerId);
            }
        }
    }
}
