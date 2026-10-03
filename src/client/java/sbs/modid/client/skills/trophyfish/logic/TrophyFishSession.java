/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.trophyfish.logic;

import sbs.modid.client.skills.trophyfish.model.TrophyTier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Trophy catches this game session, per fish and tier, plus active fishing time.
 *
 * <p><b>A session is the game session, not the lobby.</b> Trophy fishing hops Crimson Isle servers
 * constantly; a tally that reset on every hop would never show more than a few minutes. It resets on
 * restart and from the settings button, never on a world change.
 *
 * <p><b>Active time</b> is the sum of gaps between consecutive catches, counting only gaps shorter
 * than {@link #IDLE_GAP_MS}: walking to the Bazaar for twenty minutes is not fishing time. Pure (the
 * clock is passed in), so it is unit-tested.
 */
public final class TrophyFishSession {

    /** A gap longer than this between two catches is a break, not fishing. */
    public static final long IDLE_GAP_MS = 5 * 60_000L;

    private final Map<String, int[]> counts = new LinkedHashMap<>();
    private int total;
    private long lastCatchAt;
    /** Explicit, not {@code lastCatchAt > 0}: a clock value is never a safe "none" sentinel. */
    private boolean anyCatch;
    private long activeMs;

    public synchronized void record(String apiKey, TrophyTier tier, long now) {
        counts.computeIfAbsent(apiKey, k -> new int[TrophyTier.values().length])[tier.ordinal()]++;
        total++;
        if (anyCatch) {
            long gap = now - lastCatchAt;
            if (gap > 0 && gap < IDLE_GAP_MS) {
                activeMs += gap;
            }
        }
        lastCatchAt = now;
        anyCatch = true;
    }

    public synchronized void reset() {
        counts.clear();
        total = 0;
        lastCatchAt = 0;
        anyCatch = false;
        activeMs = 0;
    }

    public synchronized int count(String apiKey, TrophyTier tier) {
        int[] row = counts.get(apiKey);
        return row == null ? 0 : row[tier.ordinal()];
    }

    public synchronized int total() {
        return total;
    }

    public synchronized long activeMs() {
        return activeMs;
    }

    /** Catches per hour of active time; 0 until there is at least a minute of it. */
    public synchronized double perHour() {
        return activeMs < 60_000L ? 0 : total * 3_600_000.0 / activeMs;
    }
}
