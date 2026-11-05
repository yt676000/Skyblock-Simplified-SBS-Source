/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.perf;

/**
 * Hits, misses and invalidations of one cache, for {@code /sbs perf}.
 *
 * <p><b>Use:</b> {@code private static final CacheCounter TIPS = Perf.counter("stackTips.item");}
 * once per cache, then {@code TIPS.hit()} / {@code TIPS.miss()} / {@code TIPS.invalidate(Reason.X)}.
 * While the overlay is closed every call is one read of a static flag and nothing else - no
 * clock, no allocation.
 *
 * <p><b>Client thread only.</b> Counts are plain {@code long}s, rolled by {@link Perf#endTick} on
 * the client (render) thread. While counting, a call from any other thread is dropped (one
 * {@code Thread.currentThread()} compare), so a stray worker can never corrupt the counts - it
 * just does not show. A cache used off-thread needs its own counter design.
 */
public final class CacheCounter {

    /** Why a cache entry was dropped. */
    public enum Reason { SLOT, CONTAINER, SCREEN_CLOSE, WORLD_CHANGE, MUTATION, CAPACITY, OTHER }

    static final int HIT = 0;
    static final int MISS = 1;
    static final int FIRST_REASON = 2;
    private static final Reason[] REASONS = Reason.values();

    /** Below this hit rate over 60 s a cache is thrashing ... */
    static final double THRASH_HIT_RATE = 0.5;
    /** ... provided it was used at least this often in those 60 s (a few cold misses are not thrash). */
    static final long THRASH_MIN_ACCESSES = 100;

    private final String name;
    final CountWindow window = new CountWindow(FIRST_REASON + REASONS.length);
    private final long[] pending = window.pending;

    CacheCounter(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public void hit() {
        if (Perf.counting && Thread.currentThread() == Perf.countingThread) {
            pending[HIT]++;
        }
    }

    public void miss() {
        if (Perf.counting && Thread.currentThread() == Perf.countingThread) {
            pending[MISS]++;
        }
    }

    public void invalidate(Reason reason) {
        if (Perf.counting && Thread.currentThread() == Perf.countingThread) {
            pending[FIRST_REASON + reason.ordinal()]++;
        }
    }

    /** The published 5 s / 60 s figures; any thread. */
    public Stats stats() {
        return new Stats(name, window.snapshot());
    }

    /** A read-only view of one snapshot. */
    public record Stats(String name, CountWindow.Snapshot s) {

        public long hits60() {
            return s.sum60(HIT);
        }

        public long misses60() {
            return s.sum60(MISS);
        }

        /** Hit rate over the last 5 s, 0..1; NaN when nothing was looked up. */
        public double hitRate5() {
            long total = s.sum5(HIT) + s.sum5(MISS);
            return total == 0 ? Double.NaN : (double) s.sum5(HIT) / total;
        }

        public double hitRate60() {
            long total = hits60() + misses60();
            return total == 0 ? Double.NaN : (double) hits60() / total;
        }

        public double missesPerSec5() {
            return s.perSec5(MISS);
        }

        public double invalidationsPerSec5(Reason reason) {
            return s.perSec5(FIRST_REASON + reason.ordinal());
        }

        public double invalidationsPerSec5() {
            double sum = 0;
            for (Reason r : REASONS) {
                sum += invalidationsPerSec5(r);
            }
            return sum;
        }

        public long invalidations60(Reason reason) {
            return s.sum60(FIRST_REASON + reason.ordinal());
        }

        /** Hit rate under 50 % over 60 s with at least 100 lookups in them. */
        public boolean thrashing() {
            return hits60() + misses60() >= THRASH_MIN_ACCESSES && hitRate60() < THRASH_HIT_RATE;
        }
    }
}
