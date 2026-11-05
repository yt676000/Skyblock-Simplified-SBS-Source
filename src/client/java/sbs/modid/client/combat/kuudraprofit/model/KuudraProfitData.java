/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudraprofit.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The profile's {@code kuudra_profit.json}: the last {@link #MAX_RUNS} runs, per-tier lifetime totals
 * kept apart so pruning never changes them, and the keys bought but not yet used.
 */
public final class KuudraProfitData {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_RUNS = 500;

    public int schemaVersion = SCHEMA_VERSION;
    /** Oldest first. */
    public List<KuudraRun> runs = new ArrayList<>();
    /** {@code "T5"} -> lifetime totals; {@code "T?"} when the tier was never read. */
    public Map<String, TierTotals> tiers = new LinkedHashMap<>();
    /**
     * Tier ({@code "5"}) -> what each bought, unused key cost, oldest first; {@code null} entries are
     * keys with no price at purchase. A paid chest uses the oldest one of its tier.
     */
    public Map<String, List<Long>> keysBought = new LinkedHashMap<>();

    /** Lifetime figures for one tier. */
    public static final class TierTotals {
        public int runs;
        public int won;
        public long profit;
        public long durationMs;
        public long best = Long.MIN_VALUE;
        public boolean incomplete;
    }

    public static String tierKey(int tier) {
        return tier >= 1 && tier <= 5 ? "T" + tier : "T?";
    }
}
