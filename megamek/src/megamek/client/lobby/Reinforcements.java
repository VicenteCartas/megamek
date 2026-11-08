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
 * Microsoft's Xbox Game Studios Game Content Usage Rules
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.lobby;

import java.util.List;

import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.units.Entity;

/**
 * How units a random army or the force generator dealt join the game, as the Swing random army adds them: owned by the
 * chosen player and, once the game has started, arriving as reinforcements next round.
 */
public final class Reinforcements {
    private Reinforcements() {
    }

    /**
     * Makes {@code units} {@code owner}'s and, outside the lounge, reinforcements that deploy next round; they are
     * marked done and unloaded, else units reinforced in the movement phase are taken as selectable.
     */
    public static void prepare(List<Entity> units, Player owner, Game game) {
        boolean inPlay = !game.getPhase().isLounge();
        for (Entity unit : units) {
            unit.setOwner(owner);
            if (inPlay) {
                unit.setDeployRound(game.getRoundCount() + 1);
                unit.setGame(game);
                unit.setDone(true);
                unit.setUnloaded(true);
            }
        }
    }
}
