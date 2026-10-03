/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.logic;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One hunting session's tallies and the rates derived from them - the part with no Minecraft in it.
 *
 * <p><b>Idle time is excluded, and this is how.</b> A rate over wall-clock time is meaningless the
 * moment somebody stands in a menu for twenty minutes, and every hunting session contains long
 * stretches of that. Active time is accumulated between catches instead: the gap since the previous
 * catch counts towards the session only while it is under {@link #IDLE_AFTER_MS}, and anything longer
 * is treated as having been away. That deliberately under-counts a slow but genuine grind rather than
 * over-counting an abandoned one, because the number is used to compare methods and an inflated rate
 * argues for the wrong one.
 *
 * <p><b>No coin totals live here on purpose.</b> Shards spent levelling an attribute yield no coins,
 * and nothing in the mod can currently read which shards a player still needs - so surplus cannot be
 * separated from needed stock, and a coin figure would invite selling shards the player was saving.
 * See {@code docs/features/hunting-profit-tracker.md}. Counts and rates are safe; coins are not, yet.
 */
public final class HuntingSession {

    /** A gap longer than this did not count towards active time. Five minutes of nothing is a break. */
    public static final long IDLE_AFTER_MS = 5 * 60 * 1000L;

    /** shard name -> how many this session. Insertion order is first-seen order. */
    private final Map<String, Integer> shards = new LinkedHashMap<>();

    /** island -> how many shards were caught there. */
    private final Map<String, Integer> byIsland = new LinkedHashMap<>();

    private long startedAt;
    private long lastCatchAt;
    private long activeMs;
    private int total;
    private String rarest = "";
    private int rarestRank = -1;

    public HuntingSession() {
        this.startedAt = System.currentTimeMillis();
    }

    /**
     * Books a catch.
     *
     * @param island where it happened, from {@code SkyBlockLocation}; blank is tolerated and simply
     *               does not contribute to the per-island split
     * @param rank   a rarity ordering for "rarest this session", higher being rarer, or negative when
     *               the line carried no rarity to read
     */
    public synchronized void record(String shard, int amount, String island, int rank, long now) {
        if (shard == null || shard.isBlank() || amount <= 0) {
            return;
        }
        if (lastCatchAt != 0) {
            long gap = now - lastCatchAt;
            if (gap > 0 && gap <= IDLE_AFTER_MS) {
                activeMs += gap;
            }
        }
        lastCatchAt = now;

        shards.merge(shard, amount, Integer::sum);
        if (island != null && !island.isBlank()) {
            byIsland.merge(island, amount, Integer::sum);
        }
        total += amount;
        if (rank > rarestRank) {
            rarestRank = rank;
            rarest = shard;
        }
    }

    public synchronized int total() {
        return total;
    }

    public synchronized int uniqueSpecies() {
        return shards.size();
    }

    /** The rarest shard seen this session, or {@code ""} when nothing carried a readable rarity. */
    public synchronized String rarest() {
        return rarest;
    }

    /** Active milliseconds - wall clock minus the gaps that were breaks. */
    public synchronized long activeMs() {
        return activeMs;
    }

    public synchronized long wallClockMs() {
        return System.currentTimeMillis() - startedAt;
    }

    /**
     * Shards per hour over active time, or {@code 0} until there is enough of it to mean anything.
     *
     * <p>Below a minute of active time the figure swings wildly on a single catch, and a rate that
     * reads 4,000/h because two shards arrived eight seconds apart is worse than no rate at all.
     */
    public synchronized double shardsPerHour() {
        if (activeMs < 60_000L) {
            return 0;
        }
        return total / (activeMs / 3_600_000.0);
    }

    /** Whether {@link #shardsPerHour()} has enough active time behind it to be worth showing. */
    public synchronized boolean rateReady() {
        return activeMs >= 60_000L;
    }

    /** Shard tallies, most first. */
    public synchronized List<Map.Entry<String, Integer>> topShards(int limit) {
        return shards.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(Math.max(0, limit))
                .toList();
    }

    /** Per-island tallies, most first - the "is Torrhus better than Moonglade" answer. */
    public synchronized List<Map.Entry<String, Integer>> byIsland() {
        return byIsland.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .toList();
    }

    /** Clears everything. The session is per-run and never persisted. */
    public synchronized void reset() {
        shards.clear();
        byIsland.clear();
        startedAt = System.currentTimeMillis();
        lastCatchAt = 0;
        activeMs = 0;
        total = 0;
        rarest = "";
        rarestRank = -1;
    }
}
