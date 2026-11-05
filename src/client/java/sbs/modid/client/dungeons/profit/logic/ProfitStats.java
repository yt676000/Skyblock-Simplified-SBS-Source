/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.profit.logic;

import sbs.modid.client.dungeons.profit.model.DungeonProfitData;
import sbs.modid.client.dungeons.profit.model.DungeonRun;

import java.util.List;
import java.util.Map;

/**
 * Totals over a set of runs: count, profit, per run, per hour, best, rare drops, top drop, and the
 * cost lines.
 *
 * <p>Session and today are summed from the stored runs that <i>finished</i> in that window (a
 * Croesus claim booked later onto such a run counts with it); all-time comes from the per-floor
 * lifetime totals, which pruning never touches. Claim-only entries add coins but never a run.
 */
public record ProfitStats(int runs, long profit, long durationMs, long best, int rareDrops,
                          boolean incomplete, String topDrop, long topDropValue, Costs costs) {

    public static final ProfitStats EMPTY =
            new ProfitStats(0, 0, 0, Long.MIN_VALUE, 0, false, "", 0, Costs.NONE);

    /** The four cost lines, with how many Kismet Feathers and potions they are. */
    public record Costs(long chest, int kismets, long kismet, int potions, long potion, long extra) {

        public static final Costs NONE = new Costs(0, 0, 0, 0, 0, 0);

        public static Costs of(DungeonRun run) {
            return new Costs(run.chestCost, run.kismets, run.kismetCost, run.potions, run.potionCost, run.userCost);
        }

        public static Costs of(DungeonProfitData.FloorTotals t) {
            return new Costs(t.chestCost, t.kismets, t.kismetCost, t.potions, t.potionCost, t.userCost);
        }

        public Costs plus(Costs o) {
            return new Costs(chest + o.chest, kismets + o.kismets, kismet + o.kismet, potions + o.potions,
                    potion + o.potion, extra + o.extra);
        }

        public long total() {
            return chest + kismet + potion + extra;
        }
    }

    /** {@code ""} or {@code null} means every floor. */
    static boolean floorMatches(String filter, String floor) {
        return filter == null || filter.isEmpty() || filter.equals(floor);
    }

    /** The runs that finished at or after {@code fromMs}, on the filtered floor. */
    public static ProfitStats since(List<DungeonRun> runs, long fromMs, String floorFilter) {
        int count = 0;
        long profit = 0;
        long duration = 0;
        long best = Long.MIN_VALUE;
        int rare = 0;
        boolean incomplete = false;
        String top = "";
        long topValue = 0;
        Costs costs = Costs.NONE;
        for (DungeonRun run : runs) {
            if (run.endMs < fromMs || !floorMatches(floorFilter, run.floor)) {
                continue;
            }
            profit += run.profit();
            rare += run.rareDrops.size();
            incomplete |= run.incomplete();
            costs = costs.plus(Costs.of(run));
            if (run.topDropValue > topValue) {
                topValue = run.topDropValue;
                top = run.topDrop;
            }
            if (run.claimOnly) {
                continue;
            }
            count++;
            duration += run.durationMs();
            best = Math.max(best, run.profit());
        }
        return new ProfitStats(count, profit, duration, best, rare, incomplete, top, topValue, costs);
    }

    /** Lifetime, from the per-floor totals. */
    public static ProfitStats allTime(DungeonProfitData data, String floorFilter) {
        int count = 0;
        long profit = 0;
        long duration = 0;
        long best = Long.MIN_VALUE;
        int rare = 0;
        boolean incomplete = false;
        Costs costs = Costs.NONE;
        for (Map.Entry<String, DungeonProfitData.FloorTotals> e : data.floors.entrySet()) {
            if (!floorMatches(floorFilter, e.getKey())) {
                continue;
            }
            DungeonProfitData.FloorTotals t = e.getValue();
            count += t.runs;
            profit += t.profit;
            duration += t.durationMs;
            best = Math.max(best, t.best);
            rare += t.rareDrops;
            incomplete |= t.incomplete;
            costs = costs.plus(Costs.of(t));
        }
        // Claim-only entries are not in the floor totals: add the stored ones.
        for (DungeonRun run : data.runs) {
            if (run.claimOnly && floorMatches(floorFilter, run.floor)) {
                profit += run.profit();
                incomplete |= run.incomplete();
                costs = costs.plus(Costs.of(run));
            }
        }
        return new ProfitStats(count, profit, duration, best, rare, incomplete, "", 0, costs);
    }

    public long perRun() {
        return runs == 0 ? 0 : profit / runs;
    }

    /** Coins per hour of dungeon time, or 0 below a minute of it (too little to mean anything). */
    public long perHour() {
        return durationMs < 60_000L ? 0 : Math.round(profit / (durationMs / 3_600_000.0));
    }

    public boolean hasBest() {
        return best != Long.MIN_VALUE;
    }
}
