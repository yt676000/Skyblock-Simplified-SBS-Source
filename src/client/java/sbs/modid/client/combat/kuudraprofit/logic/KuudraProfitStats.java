/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudraprofit.logic;

import sbs.modid.client.combat.kuudraprofit.model.KuudraProfitData;
import sbs.modid.client.combat.kuudraprofit.model.KuudraRun;

import java.util.List;
import java.util.Map;

/**
 * Totals over a set of Kuudra runs. Session and today are summed from the stored runs that ended in
 * that window; all-time comes from the per-tier lifetime totals, which pruning never touches. A tier
 * filter of 0 means every tier.
 */
public record KuudraProfitStats(int runs, int won, long profit, long durationMs, long best, boolean incomplete,
                                String topDrop, long topDropValue) {

    static boolean tierMatches(int filter, int tier) {
        return filter == 0 || filter == tier;
    }

    /** The runs that ended at or after {@code fromMs}, on the filtered tier. */
    public static KuudraProfitStats since(List<KuudraRun> runs, long fromMs, int tierFilter) {
        int count = 0;
        int won = 0;
        long profit = 0;
        long duration = 0;
        long best = Long.MIN_VALUE;
        boolean incomplete = false;
        String top = "";
        long topValue = 0;
        for (KuudraRun run : runs) {
            if (run.endMs < fromMs || !tierMatches(tierFilter, run.tier)) {
                continue;
            }
            count++;
            if (run.won) {
                won++;
            }
            profit += run.profit();
            duration += run.durationMs;
            incomplete |= run.incomplete();
            if (run.chests() > 0) {
                best = Math.max(best, run.profit());
            }
            if (run.topDropValue > topValue) {
                topValue = run.topDropValue;
                top = run.topDrop;
            }
        }
        return new KuudraProfitStats(count, won, profit, duration, best, incomplete, top, topValue);
    }

    /** Lifetime, from the per-tier totals. */
    public static KuudraProfitStats allTime(KuudraProfitData data, int tierFilter) {
        int count = 0;
        int won = 0;
        long profit = 0;
        long duration = 0;
        long best = Long.MIN_VALUE;
        boolean incomplete = false;
        for (Map.Entry<String, KuudraProfitData.TierTotals> e : data.tiers.entrySet()) {
            if (tierFilter != 0 && !KuudraProfitData.tierKey(tierFilter).equals(e.getKey())) {
                continue;
            }
            KuudraProfitData.TierTotals t = e.getValue();
            count += t.runs;
            won += t.won;
            profit += t.profit;
            duration += t.durationMs;
            best = Math.max(best, t.best);
            incomplete |= t.incomplete;
        }
        return new KuudraProfitStats(count, won, profit, duration, best, incomplete, "", 0);
    }

    public long perRun() {
        return runs == 0 ? 0 : profit / runs;
    }

    /** Coins per hour of run time, or 0 below a minute of it. */
    public long perHour() {
        return durationMs < 60_000L ? 0 : Math.round(profit / (durationMs / 3_600_000.0));
    }

    public boolean hasBest() {
        return best != Long.MIN_VALUE;
    }
}
