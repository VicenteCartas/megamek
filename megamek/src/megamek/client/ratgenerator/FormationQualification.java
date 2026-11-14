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
package megamek.client.ratgenerator;

import java.util.List;

import jakarta.annotation.Nullable;
import megamek.common.loaders.MekSummary;
import megamek.common.units.UnitRole;

/**
 * How a list of units measures up to a {@link FormationType}, requirement by requirement, as
 * {@link FormationType#qualify(List)} finds it: what callers that show their own warnings need, where
 * {@link FormationType#qualificationReport(List)} gives finished HTML.
 *
 * <p>Unit roles aren't known for every unit. When the units fall short only of requirements that could hang on a role
 * and some of them have none, the result is {@link Verdict#UNVERIFIED} rather than {@link Verdict#FAILS}: the rule may
 * well be met.
 *
 * @param formation    the formation's name
 * @param requirements every requirement the formation sets, in the order the rules list them
 * @param idealRole    the role that, if every unit has it, meets the formation by itself; null if it has none
 * @param idealRoleMet whether every unit has the ideal role
 * @param unknownRoles the units whose role isn't known
 */
public record FormationQualification(String formation, List<Requirement> requirements, @Nullable UnitRole idealRole,
      boolean idealRoleMet, List<MekSummary> unknownRoles) {

    /** Whether the units meet the formation, fall short of it, or can't be told for want of roles. */
    public enum Verdict {
        QUALIFIES, FAILS, UNVERIFIED
    }

    /** What a requirement asks of the units. */
    public enum Kind {
        /** Every unit is of a type the formation allows. */
        UNIT_TYPE(true),
        /** Every unit is within the formation's weight classes. */
        WEIGHT_CLASS(true),
        /** Every unit meets the formation's main criterion, such as a speed or armor. */
        MAIN(true),
        /** A number or share of the units meet a criterion, often a role. */
        CONSTRAINT(false),
        /** Some of the units make up matched groups, such as pairs of the same chassis. */
        GROUPING(false);

        private final boolean everyUnit;

        Kind(boolean everyUnit) {
            this.everyUnit = everyUnit;
        }

        /** Whether the requirement holds of every unit, so each one missing it is at fault. */
        public boolean everyUnit() {
            return everyUnit;
        }
    }

    /**
     * One requirement and how the units meet it.
     *
     * @param kind        what it asks
     * @param description what the rules call it, such as {@code "Sniper"} or {@code "Armor 105+"}
     * @param needed      how many units must meet it
     * @param matching    the units that meet it; for a grouping, those in a complete group
     * @param met         whether it's met; one of an "or" pair is met when either of the pair is
     * @param orPrevious  whether it's the second of an "or" pair, the alternative to the requirement before it
     */
    public record Requirement(Kind kind, String description, int needed, List<MekSummary> matching, boolean met,
          boolean orPrevious) {
        public Requirement {
            matching = List.copyOf(matching);
        }

        /** How many more units it would take to meet it on its own. */
        public int shortfall() {
            return Math.max(0, needed - matching.size());
        }
    }

    public FormationQualification {
        requirements = List.copyOf(requirements);
        unknownRoles = List.copyOf(unknownRoles);
    }

    /**
     * Whether the units meet the formation: each is of a type it allows, and either every unit has its ideal role or
     * every requirement is met.
     */
    public boolean qualifies() {
        boolean types = requirements.stream()
              .filter(requirement -> requirement.kind() == Kind.UNIT_TYPE)
              .allMatch(Requirement::met);
        return types && (idealRoleMet || requirements.stream().allMatch(Requirement::met));
    }

    /** The requirements the units fall short of. */
    public List<Requirement> unmet() {
        return requirements.stream().filter(requirement -> !requirement.met()).toList();
    }

    /**
     * Whether the units meet the formation; if they don't, whether that's certain, or could be down to units whose role
     * isn't known, the unit types and weight classes being right.
     */
    public Verdict verdict() {
        if (qualifies()) {
            return Verdict.QUALIFIES;
        }
        if (unknownRoles.isEmpty()) {
            return Verdict.FAILS;
        }
        boolean certain = unmet().stream()
              .anyMatch(requirement -> requirement.kind() == Kind.UNIT_TYPE
                    || requirement.kind() == Kind.WEIGHT_CLASS);
        return certain ? Verdict.FAILS : Verdict.UNVERIFIED;
    }
}
