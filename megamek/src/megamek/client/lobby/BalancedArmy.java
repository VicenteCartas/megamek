/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

import jakarta.annotation.Nullable;
import megamek.common.loaders.MekSummary;
import megamek.common.util.SimpleRandomLanceCreator;

/**
 * A random lance of so many units whose strength comes close to a target, without a toolkit, as the Swing
 * {@code RandomArmyDialog}'s Simple tab builds it with {@link SimpleRandomLanceCreator}: the strength may be Battle
 * Value, Point Value, cost or weight. Kept cards count against the target.
 */
public final class BalancedArmy implements RandomArmyForm.Dealer {

    /** What the lance is balanced by, with the targets offered for it. */
    public enum BalanceBy {
        BV(MekSummary::getBV, 1000, 2000, 3000, 4000, 5000, 6000),
        PV(MekSummary::getPointValue, 20, 40, 60, 80, 100, 120),
        CBILLS(unit -> (double) unit.getCost(), 2_500_000, 5_000_000, 7_500_000, 10_000_000, 15_000_000,
              20_000_000, 50_000_000),
        TONS(MekSummary::getTons, 80, 100, 125, 150, 200, 320, 400),
        KG(unit -> unit.getTons() * 1000, 200, 500, 1000, 2000, 5000, 8000);

        private final ToDoubleFunction<MekSummary> strength;
        private final int[] targets;

        BalanceBy(ToDoubleFunction<MekSummary> strength, int... targets) {
            this.strength = strength;
            this.targets = targets;
        }

        public double strength(MekSummary unit) {
            return strength.applyAsDouble(unit);
        }

        /** The targets offered, smallest first. */
        public int[] targets() {
            return targets.clone();
        }

        /** The message key of its name, the Swing tab's. */
        public String key() {
            return switch (this) {
                case BV -> "RandomArmyDialog.Simple.BV_NAME";
                case PV -> "RandomArmyDialog.Simple.PV_NAME";
                case CBILLS -> "RandomArmyDialog.Simple.CBILL_NAME";
                case TONS -> "RandomArmyDialog.Simple.TONS_NAME";
                case KG -> "RandomArmyDialog.Simple.KG_NAME";
            };
        }
    }

    /** The tolerances offered, in percent of the target. */
    public static final List<Integer> TOLERANCES = List.of(0, 5, 10, 25, 50);

    private final Supplier<MekSummary[]> roster;
    private BalanceBy balanceBy = BalanceBy.BV;
    private int target = 4000;
    private int tolerance;
    private @Nullable Predicate<MekSummary> filter;
    private @Nullable List<MekSummary> pool;

    /** @param roster every unit there is, read when the lance is first dealt or counted */
    public BalancedArmy(Supplier<MekSummary[]> roster) {
        this.roster = Objects.requireNonNull(roster);
    }

    public BalanceBy balanceBy() {
        return balanceBy;
    }

    /** Balances by {@code balanceBy}; the target moves to the one offered at the same place, as units change. */
    public void balanceBy(BalanceBy balanceBy) {
        if (balanceBy == this.balanceBy) {
            return;
        }
        int index = Math.max(0, indexOf(this.balanceBy.targets, target));
        this.balanceBy = balanceBy;
        int[] targets = balanceBy.targets;
        target = targets[Math.min(index, targets.length - 1)];
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return -1;
    }

    public int target() {
        return target;
    }

    public void target(int target) {
        this.target = Math.max(0, target);
    }

    /** The tolerance in percent of the target. */
    public int tolerance() {
        return tolerance;
    }

    public void tolerance(int percent) {
        tolerance = Math.clamp(percent, 0, 100);
    }

    /** Narrows the units to those {@code filter} accepts, or none for null. */
    public void filter(@Nullable Predicate<MekSummary> filter) {
        this.filter = filter;
        pool = null;
    }

    /** The units the lance may be dealt. */
    public int available() {
        return pool().size();
    }

    private List<MekSummary> pool() {
        if (pool == null) {
            List<MekSummary> units = new ArrayList<>();
            for (MekSummary unit : roster.get()) {
                if (filter == null || filter.test(unit)) {
                    units.add(unit);
                }
            }
            pool = units;
        }
        return pool;
    }

    /** The strength of {@code units}, by what the lance is balanced by. */
    public double strength(List<MekSummary> units) {
        double strength = 0;
        for (MekSummary unit : units) {
            strength += balanceBy.strength(unit);
        }
        return strength;
    }

    @Override
    public boolean canDeal() {
        return !pool().isEmpty();
    }

    @Override
    public List<MekSummary> deal(int count, List<MekSummary> kept) {
        int left = (int) Math.max(0, target - strength(kept));
        return new SimpleRandomLanceCreator<>(balanceBy.strength).buildForce(pool(), count, left,
              (int) ((long) tolerance * target / 100));
    }

    /** A unit about as strong as {@code unit}, so the lance stays near its target. */
    @Override
    public @Nullable MekSummary replace(MekSummary unit, List<MekSummary> rest) {
        int strength = (int) balanceBy.strength(unit);
        List<MekSummary> picked = new SimpleRandomLanceCreator<>(balanceBy.strength).buildForce(pool(), 1, strength,
              Math.max(1, (int) (target * Math.max(tolerance, 10) / 100.0 / Math.max(1, rest.size() + 1))));
        return picked.isEmpty() ? null : picked.get(0);
    }
}
