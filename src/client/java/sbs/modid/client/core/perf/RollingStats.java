/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.perf;

/**
 * Rolling cost statistics for one measured section: one sample per frame (or per tick) - the time
 * the section took in that frame - kept as per-second buckets over the last {@value #SECONDS}
 * seconds. Pure: time is passed in, nothing allocates after construction.
 *
 * <p>Each bucket holds the sum, count and maximum of its samples, the calls made, and a
 * logarithmic histogram, so the 95th percentile of any window is answered without keeping every
 * sample. The percentile is the upper edge of the histogram bucket it falls in - at most a factor of
 * two high, never low, which is the safe direction for a budget check.
 */
public final class RollingStats {

    static final int SECONDS = 60;

    /** Histogram: bucket {@code i} holds samples below {@code BASE_NS << i}; the last holds the rest. */
    static final int BINS = 24;
    static final long BASE_NS = 500L;

    private final long[] sum = new long[SECONDS];
    private final long[] samples = new long[SECONDS];
    private final long[] max = new long[SECONDS];
    private final long[] calls = new long[SECONDS];
    private final long[][] hist = new long[SECONDS][BINS];
    /** Which absolute second each slot currently holds; a stale slot is cleared before reuse. */
    private final long[] slotSecond = new long[SECONDS];

    public RollingStats() {
        java.util.Arrays.fill(slotSecond, Long.MIN_VALUE);
    }

    /** One frame's (or tick's) total for this section, with the calls it made. */
    public void sample(long ns, int callCount, long nowMs) {
        long second = Math.floorDiv(nowMs, 1000L);
        int slot = (int) Math.floorMod(second, SECONDS);
        if (slotSecond[slot] != second) {
            slotSecond[slot] = second;
            sum[slot] = 0;
            samples[slot] = 0;
            max[slot] = 0;
            calls[slot] = 0;
            java.util.Arrays.fill(hist[slot], 0);
        }
        sum[slot] += ns;
        samples[slot]++;
        calls[slot] += callCount;
        if (ns > max[slot]) {
            max[slot] = ns;
        }
        hist[slot][bin(ns)]++;
    }

    /** The histogram bucket of a sample. */
    static int bin(long ns) {
        int i = 0;
        long edge = BASE_NS;
        while (i < BINS - 1 && ns >= edge) {
            edge <<= 1;
            i++;
        }
        return i;
    }

    /** The upper edge of a histogram bucket, in nanoseconds. */
    static long binTop(int bin) {
        return BASE_NS << bin;
    }

    /** What a window shows. Times in milliseconds; rates per second. */
    public record Summary(double avgMs, double p95Ms, double maxMs, double samplesPerSec,
                          double callsPerSec, long sampleCount) {
        public static final Summary EMPTY = new Summary(0, 0, 0, 0, 0, 0);
    }

    /** The last {@code windowSec} seconds, the current (partial) second included. */
    public Summary summary(int windowSec, long nowMs) {
        int window = Math.max(1, Math.min(SECONDS, windowSec));
        long now = Math.floorDiv(nowMs, 1000L);
        long total = 0;
        long count = 0;
        long top = 0;
        long callTotal = 0;
        long[] merged = new long[BINS];
        int secondsWithData = 0;
        for (long second = now - window + 1; second <= now; second++) {
            int slot = (int) Math.floorMod(second, SECONDS);
            if (slotSecond[slot] != second || samples[slot] == 0) {
                continue;
            }
            secondsWithData++;
            total += sum[slot];
            count += samples[slot];
            callTotal += calls[slot];
            top = Math.max(top, max[slot]);
            for (int b = 0; b < BINS; b++) {
                merged[b] += hist[slot][b];
            }
        }
        if (count == 0) {
            return Summary.EMPTY;
        }
        long target = (long) Math.ceil(count * 0.95);
        long seen = 0;
        long p95 = top;
        for (int b = 0; b < BINS; b++) {
            seen += merged[b];
            if (seen >= target) {
                p95 = Math.min(binTop(b), top);
                break;
            }
        }
        int span = Math.max(1, secondsWithData);
        return new Summary(total / (double) count / 1e6, p95 / 1e6, top / 1e6,
                count / (double) span, callTotal / (double) span, count);
    }

    /** Forgets everything. */
    public void reset() {
        java.util.Arrays.fill(slotSecond, Long.MIN_VALUE);
    }
}
