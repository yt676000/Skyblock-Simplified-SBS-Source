/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.nucleus.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shape of {@code nucleus_runs.json}, one per account and SkyBlock profile.
 *
 * <p>Plain Gson fields, no game objects. Field names are the stored format: renaming one loses that
 * field from every saved file. {@link #schemaVersion} goes up only when a change cannot be read by an
 * older build; a file with a higher one is refused rather than half-read.
 */
public final class NucleusRunData {

    public static final int SCHEMA_VERSION = 1;

    public int schemaVersion = SCHEMA_VERSION;
    /** The run in progress, or {@code null} before the first Crystal Hollows visit. */
    public Run current;
    /** Finished runs, oldest first, at most {@code NucleusRunLedger.MAX_RUNS}. */
    public List<Run> runs = new ArrayList<>();
    /** Lifetime figures, kept apart from {@link #runs} so pruning the history never changes them. */
    public Totals totals = new Totals();
    /** Explicit loot still expected to show up as a {@code [Sacks]} gain. */
    public List<Arrival> arrivals = new ArrayList<>();

    /** One run, open or finished. */
    public static final class Run {
        /** Wall clock, epoch ms. */
        public long startedAt;
        /** Wall clock, epoch ms; {@code 0} while open. */
        public long endedAt;
        /** Time online on the Crystal Hollows during the run. */
        public long onlineMs;
        /** {@link Crystal#name()} to {@link Crystal.State#name()}. */
        public Map<String, String> crystals = new LinkedHashMap<>();
        /** Set when the run was not watched from start to bundle - its figures are still counted. */
        public boolean partial;

        /** The bundle's items. */
        public List<Line> nucleus = new ArrayList<>();
        /** Chest loot and sack gains during the run. */
        public List<Line> loot = new ArrayList<>();
        /** Items used up. */
        public List<Line> costs = new ArrayList<>();
        /** HotM XP and powder, by kind - not coins. */
        public Map<String, Long> nonCoin = new LinkedHashMap<>();

        // Written when the run finishes, from the price snapshot on each line.
        public String priceSide;
        public boolean selfObtainedCounted;
        public long nucleusRevenue;
        public long lootRevenue;
        public long costTotal;
        public long nucleusProfit;
        public long runProfit;

        public boolean finished() {
            return endedAt > 0;
        }
    }

    /** One item in a bucket. Repeats of the same item are merged into one line. */
    public static final class Line {
        public String name;
        /** SkyBlock id, or {@code null} when the name could not be resolved (then unpriced). */
        public String id;
        public long qty;
        /** Unit price at the moment the run finished; {@code -1} when unpriced or still open. */
        public long unitPrice = -1L;
        /** Costs only: how many of {@link #qty} were not charged because the run looted them. */
        public long free;
        /** Costs only: the {@code NucleusCostRules} rule id. */
        public String rule;
        /** Costs only: booked from a fallback rather than an observed decrease. */
        public boolean estimated;

        public Line() {
        }

        public Line(String name, String id, long qty) {
            this.name = name;
            this.id = id;
            this.qty = qty;
        }
    }

    /** Lifetime figures over every finished run. */
    public static final class Totals {
        public long runs;
        public long nucleusProfit;
        public long runProfit;
        public long onlineMs;
    }

    /** An item announced by a reward block that has not reached a sack yet. */
    public static final class Arrival {
        /** Lower-case display name, glyph removed - the sack hover only has names. */
        public String key;
        public long qty;
        /** Epoch ms. */
        public long expiresAt;

        public Arrival() {
        }

        public Arrival(String key, long qty, long expiresAt) {
            this.key = key;
            this.qty = qty;
            this.expiresAt = expiresAt;
        }
    }
}
