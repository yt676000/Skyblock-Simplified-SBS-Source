/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.profit.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The profile's {@code dungeon_profit.json}: the last {@link #MAX_RUNS} runs, and per-floor lifetime
 * totals kept apart so pruning old runs never changes them.
 */
public final class DungeonProfitData {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_RUNS = 500;

    public int schemaVersion = SCHEMA_VERSION;
    /** Oldest first. */
    public List<DungeonRun> runs = new ArrayList<>();
    /** The run in progress, kept so a crash mid-run loses nothing. */
    public DungeonRun current;
    /** Floor label -> lifetime totals. */
    public Map<String, FloorTotals> floors = new LinkedHashMap<>();

    /** Lifetime figures for one floor. */
    public static final class FloorTotals {
        public int runs;
        public long profit;
        public long durationMs;
        public long best = Long.MIN_VALUE;
        public int rareDrops;
        /** Whether any run in here had something without a price. */
        public boolean incomplete;
        /** The cost lines summed; 0 in a file from before they were kept. */
        public long chestCost;
        public int kismets;
        public long kismetCost;
        public int potions;
        public long potionCost;
        public long userCost;
    }
}
