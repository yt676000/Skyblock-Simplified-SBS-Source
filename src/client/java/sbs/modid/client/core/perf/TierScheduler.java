/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.perf;

/**
 * The mod-wide budget: the {@code MenuRenderPriority} rule generalised to any per-frame or per-tick
 * work. Pure - the caller passes the window's cost and the time - so the shed order, hysteresis and
 * heartbeat are unit-tested.
 *
 * <ul>
 *   <li>Within budget everything runs.</li>
 *   <li>Over budget (smoothed over a few windows, with hysteresis so it does not flicker):
 *       {@link Tier#BACKGROUND} work is deferred but still runs at least every
 *       {@link #backgroundHeartbeatMs}; {@link Tier#NORMAL} work drops to one run every
 *       {@link #normalIntervalMs}; {@link Tier#CRITICAL} always runs.</li>
 * </ul>
 * Deferred, never abandoned: work wrongly marked BACKGROUND degrades to a slow update rate - visible
 * and fixable - instead of vanishing.
 */
public final class TierScheduler {

    /** How much a piece of work matters right now. */
    public enum Tier { CRITICAL, NORMAL, BACKGROUND }

    private static final int SMOOTHING = 4;

    private final long budgetNs;
    private final long resumeNs;
    final long normalIntervalMs;
    final long backgroundHeartbeatMs;

    private long smoothedNs;
    private boolean shedding;
    private long[] lastRunMs = new long[64];
    private int skipped;

    public TierScheduler(long budgetNs, long normalIntervalMs, long backgroundHeartbeatMs) {
        this.budgetNs = budgetNs;
        this.resumeNs = budgetNs * 2 / 3;   // hysteresis: resume well below the budget
        this.normalIntervalMs = normalIntervalMs;
        this.backgroundHeartbeatMs = backgroundHeartbeatMs;
        java.util.Arrays.fill(lastRunMs, Long.MIN_VALUE / 2);
    }

    /** Closes a window (one frame or one tick) that cost {@code spentNs}. Returns whether shedding. */
    public boolean endWindow(long spentNs) {
        smoothedNs = smoothedNs == 0 ? spentNs : (smoothedNs * SMOOTHING + spentNs) / (SMOOTHING + 1);
        if (smoothedNs > budgetNs) {
            shedding = true;
        } else if (smoothedNs < resumeNs) {
            shedding = false;
        }
        skipped = 0;
        return shedding;
    }

    /** Whether work {@code id} of {@code tier} may run now; records the run when it may. */
    public boolean allow(int id, Tier tier, long nowMs) {
        ensure(id);
        if (tier == Tier.CRITICAL || !shedding) {
            lastRunMs[id] = nowMs;
            return true;
        }
        long interval = tier == Tier.NORMAL ? normalIntervalMs : backgroundHeartbeatMs;
        if (nowMs - lastRunMs[id] >= interval) {
            lastRunMs[id] = nowMs;
            return true;
        }
        skipped++;
        return false;
    }

    public boolean shedding() {
        return shedding;
    }

    public long smoothedNs() {
        return smoothedNs;
    }

    /** Work skipped in the current window. */
    public int skipped() {
        return skipped;
    }

    private void ensure(int id) {
        if (id >= lastRunMs.length) {
            long[] grown = java.util.Arrays.copyOf(lastRunMs, Math.max(id + 1, lastRunMs.length * 2));
            java.util.Arrays.fill(grown, lastRunMs.length, grown.length, Long.MIN_VALUE / 2);
            lastRunMs = grown;
        }
    }
}
