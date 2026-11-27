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

import static megamek.client.bot.princess.BehaviorChange.Trait.AGGRESSION;
import static megamek.client.bot.princess.BehaviorChange.Trait.BRAVERY;
import static megamek.client.bot.princess.BehaviorChange.Trait.FALL_SHAME;
import static megamek.client.bot.princess.BehaviorChange.Trait.MUTUAL_SUPPORT;
import static megamek.client.bot.princess.BehaviorChange.Trait.SELF_PRESERVATION;

import java.util.List;

/**
 * The quick tactics a player can give an allied bot for the moment, such as Alpha Strike or Fall Back. Each sets a few
 * behavior indices and leaves the rest as they are; No Prisoners instead drops the bot's mercy. Both bot command UIs
 * offer the same tactics from this table.
 */
public enum Tactic {
    ALPHA_STRIKE("AlphaStrike", index(BRAVERY, 10), index(AGGRESSION, 10), index(SELF_PRESERVATION, 5)),
    /** Forgets every ignored unit and declares a blood feud on every enemy player. */
    NO_PRISONERS("NoPrisoners"),
    STAY_AT_RANGE("StayAtRange", index(BRAVERY, 8), index(AGGRESSION, 3), index(SELF_PRESERVATION, 7)),
    DISPERSE("Disperse", index(MUTUAL_SUPPORT, 0)),
    TIGHT_FORMATION("TightFormation", index(MUTUAL_SUPPORT, 9)),
    LOOSE_FORMATION("LooseFormation", index(MUTUAL_SUPPORT, 3)),
    HOLD_THE_LINE("HoldTheLine", index(SELF_PRESERVATION, 2), index(BRAVERY, 4), index(AGGRESSION, 10),
          index(FALL_SHAME, 7)),
    DOUBLE_TIME_MARCH("DoubleTimeMarch", index(SELF_PRESERVATION, 2), index(FALL_SHAME, 2)),
    FINAL_GLORY("FinalGlory", index(SELF_PRESERVATION, 0), index(BRAVERY, 10), index(AGGRESSION, 10),
          index(FALL_SHAME, 6)),
    FALL_BACK("FallBack", index(SELF_PRESERVATION, 8), index(BRAVERY, 3), index(AGGRESSION, 3),
          index(FALL_SHAME, 7)),
    EVASIVE_ACTION("EvasiveAction", index(SELF_PRESERVATION, 10), index(BRAVERY, 3), index(AGGRESSION, 6),
          index(FALL_SHAME, 2)),
    CAREFUL_AIM("CarefulAim", index(SELF_PRESERVATION, 6), index(BRAVERY, 8), index(AGGRESSION, 3),
          index(FALL_SHAME, 7));

    private final String key;
    private final List<BehaviorChange.Index> indices;

    Tactic(String key, BehaviorChange.Index... indices) {
        this.key = key;
        this.indices = List.of(indices);
    }

    private static BehaviorChange.Index index(BehaviorChange.Trait trait, int value) {
        return new BehaviorChange.Index(trait, value);
    }

    /**
     * @return the name this tactic's texts go by in the client messages, e.g. {@code BotCommandPanel.<key>.title}
     */
    public String key() {
        return key;
    }

    /** @return the behavior indices this tactic sets; empty for {@link #NO_PRISONERS} */
    public List<BehaviorChange.Index> indices() {
        return indices;
    }

    /**
     * @return true when this tactic drops the bot's mercy: the sender clears its ignore list and declares a blood feud
     *       on every enemy player
     */
    public boolean dropsMercy() {
        return this == NO_PRISONERS;
    }
}
