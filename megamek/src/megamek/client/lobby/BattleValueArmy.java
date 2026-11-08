/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

import jakarta.annotation.Nullable;
import megamek.common.TechConstants;
import megamek.common.loaders.MekSummary;
import megamek.common.util.RandomArmyCreator;
import megamek.common.util.SimpleRandomLanceCreator;

/**
 * A random army of so many Meks, vehicles, battle armor and infantry within a Battle Value budget, without a toolkit,
 * as the Swing {@code RandomArmyDialog}'s BV Matching tab builds it with {@link RandomArmyCreator}. Kept cards count
 * against both: a roll deals only the units still missing of each type, for what is left of the budget.
 */
public final class BattleValueArmy implements RandomArmyForm.Dealer {

    /** The unit types the army is counted in. */
    public enum Kind {
        MEK, VEHICLE, BATTLE_ARMOR, INFANTRY;

        /** The kind {@code unit} is counted as, or null when the army has no count for it. */
        public static @Nullable Kind of(MekSummary unit) {
            if (unit.isMek()) {
                return MEK;
            } else if (unit.isVehicle()) {
                return VEHICLE;
            } else if (unit.isBattleArmor()) {
                return BATTLE_ARMOR;
            } else if (unit.isConventionalInfantry()) {
                return INFANTRY;
            }
            return null;
        }
    }

    /** The most units of one kind an army asks for. */
    public static final int MAX_COUNT = 12;
    /** The most infantry padding adds to an army, so a large budget does not deal hundreds of cards. */
    public static final int MAX_PADDING = 4 * MAX_COUNT;
    public static final int MAX_BV = 100_000;
    public static final int MIN_YEAR = 1950;
    public static final int MAX_YEAR = 3250;

    private final Supplier<MekSummary[]> roster;
    private final int[] counts = { 4, 0, 0, 0 };
    private int minBV = 4000;
    private int maxBV = 8000;
    private int minYear;
    private int maxYear;
    private boolean canon;
    private boolean pad;
    private boolean cutShort;
    private @Nullable Predicate<MekSummary> filter;
    private @Nullable List<MekSummary> pool;

    /**
     * @param roster   every unit there is, read when the army is first dealt or counted
     * @param gameYear the game's year: the army's units are designed in the half century before it, as the Swing tab
     *                 offers 3000-3047 for the default 3047
     */
    public BattleValueArmy(Supplier<MekSummary[]> roster, int gameYear) {
        this.roster = Objects.requireNonNull(roster);
        maxYear = Math.clamp(gameYear, MIN_YEAR, MAX_YEAR);
        minYear = Math.max(MIN_YEAR, Math.min(3000, maxYear - 50));
    }

    public int count(Kind kind) {
        return counts[kind.ordinal()];
    }

    public void count(Kind kind, int count) {
        counts[kind.ordinal()] = Math.clamp(count, 0, MAX_COUNT);
    }

    /** The units a full hand holds: every kind's count. */
    public int size() {
        int size = 0;
        for (int count : counts) {
            size += count;
        }
        return size;
    }

    public int minBV() {
        return minBV;
    }

    public int maxBV() {
        return maxBV;
    }

    /** Sets the budget; a minimum over the maximum is taken as the maximum, as the bounds of a range. */
    public void bv(int min, int max) {
        maxBV = Math.clamp(max, 0, MAX_BV);
        minBV = Math.clamp(min, 0, maxBV);
    }

    public int minYear() {
        return minYear;
    }

    public int maxYear() {
        return maxYear;
    }

    public void years(int min, int max) {
        maxYear = Math.clamp(max, MIN_YEAR, MAX_YEAR);
        minYear = Math.clamp(min, MIN_YEAR, maxYear);
        pool = null;
    }

    public boolean canon() {
        return canon;
    }

    public void canon(boolean canon) {
        this.canon = canon;
        pool = null;
    }

    /** Whether spare Battle Value is padded with infantry, as the Swing tab offers. */
    public boolean pad() {
        return pad;
    }

    public void pad(boolean pad) {
        this.pad = pad;
    }

    /**
     * Narrows the units to those {@code filter} accepts, or none for null; it does not apply to infantry and battle
     * armor, as the Swing advanced search does not.
     */
    public void filter(@Nullable Predicate<MekSummary> filter) {
        this.filter = filter;
        pool = null;
    }

    /** The units the army may be dealt of {@code kind}. */
    public int available(Kind kind) {
        int available = 0;
        for (MekSummary unit : pool()) {
            if (Kind.of(unit) == kind) {
                available++;
            }
        }
        return available;
    }

    /** Whether the army asks for units of {@code kind} that none of the units can be. */
    public boolean shortOf(Kind kind) {
        return count(kind) > 0 && available(kind) == 0;
    }

    private List<MekSummary> pool() {
        if (pool == null) {
            List<MekSummary> units = new ArrayList<>();
            for (MekSummary unit : roster.get()) {
                Kind kind = Kind.of(unit);
                if (kind != null && accepts(unit, kind)) {
                    units.add(unit);
                }
            }
            pool = units;
        }
        return pool;
    }

    // As RandomArmyCreator filters: infantry ignore the years, infantry and battle armor the filter
    private boolean accepts(MekSummary unit, Kind kind) {
        if (kind != Kind.INFANTRY && (unit.getYear() < minYear || unit.getYear() > maxYear)) {
            return false;
        }
        if (canon && !unit.isCanon()) {
            return false;
        }
        return kind == Kind.INFANTRY || kind == Kind.BATTLE_ARMOR || filter == null || filter.test(unit);
    }

    @Override
    public boolean canDeal() {
        return size() > 0;
    }

    @Override
    public boolean usesCount() {
        return false;
    }

    @Override
    public List<MekSummary> deal(int count, List<MekSummary> kept) {
        cutShort = false;
        int[] wanted = counts.clone();
        int keptBV = 0;
        for (MekSummary unit : kept) {
            Kind kind = Kind.of(unit);
            if (kind != null) {
                wanted[kind.ordinal()] = Math.max(0, wanted[kind.ordinal()] - 1);
            }
            keptBV += unit.getBV();
        }
        if (wanted[0] + wanted[1] + wanted[2] + wanted[3] == 0) {
            return List.of();
        }
        RandomArmyCreator.Parameters parameters = new RandomArmyCreator.Parameters();
        parameters.meks = wanted[Kind.MEK.ordinal()];
        parameters.tanks = wanted[Kind.VEHICLE.ordinal()];
        parameters.ba = wanted[Kind.BATTLE_ARMOR.ordinal()];
        parameters.infantry = wanted[Kind.INFANTRY.ordinal()];
        parameters.maxBV = Math.max(0, maxBV - keptBV);
        parameters.minBV = Math.max(0, minBV - keptBV);
        parameters.minYear = minYear;
        parameters.maxYear = maxYear;
        parameters.canon = canon;
        parameters.padWithInfantry = pad;
        parameters.maxPadding = MAX_PADDING;
        parameters.tech = TechConstants.T_ALL;
        List<MekSummary> army = RandomArmyCreator.generateArmy(parameters, pool().toArray(new MekSummary[0]));
        cutShort = parameters.paddingLimited;
        return army;
    }

    /** Whether the last army dealt stopped padding at {@link #MAX_PADDING} infantry, short of its budget. */
    @Override
    public boolean cutShort() {
        return cutShort;
    }

    /** A unit of the same kind and about the same Battle Value, so the army stays within its budget. */
    @Override
    public @Nullable MekSummary replace(MekSummary unit, List<MekSummary> rest) {
        Kind kind = Kind.of(unit);
        List<MekSummary> same = new ArrayList<>();
        for (MekSummary candidate : pool()) {
            if (Kind.of(candidate) == kind && candidate != unit) {
                same.add(candidate);
            }
        }
        List<MekSummary> picked = SimpleRandomLanceCreator.mekSummaryWithBv()
              .buildForce(same, 1, unit.getBV(), Math.max(1, unit.getBV() / 10));
        return picked.isEmpty() ? null : picked.get(0);
    }
}
