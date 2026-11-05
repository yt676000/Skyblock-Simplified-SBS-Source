/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.perf;

/**
 * Event counts in per-second buckets over the last 60 s, for {@link CacheCounter} and the per-section
 * skip counts.
 *
 * <p>Counting goes into {@link #pending} (plain {@code long}s, the owner thread only). {@link #roll}
 * - on the same thread, from {@link Perf#endTick} - moves a finished second into the ring and
 * publishes the 5 s / 60 s sums as one immutable {@link Snapshot} in a volatile field, so any thread
 * may read it. One snapshot is allocated per second, never per event.
 */
final class CountWindow {

    static final int SECONDS = 60;

    /** The sums over the last 5 s and 60 s, per channel. Immutable once published. */
    record Snapshot(long[] last5, long[] last60, double span5Sec, double span60Sec) {

        long sum5(int channel) {
            return last5[channel];
        }

        long sum60(int channel) {
            return last60[channel];
        }

        double perSec5(int channel) {
            return span5Sec > 0 ? last5[channel] / span5Sec : 0;
        }

        double perSec60(int channel) {
            return span60Sec > 0 ? last60[channel] / span60Sec : 0;
        }
    }

    private final int channels;
    final long[] pending;
    private final long[] ring;
    private final long[] ringSecond;
    private long currentSecond = Long.MIN_VALUE;
    private long firstSecond = Long.MIN_VALUE;
    private volatile Snapshot snapshot;

    CountWindow(int channels) {
        this.channels = channels;
        this.pending = new long[channels];
        this.ring = new long[SECONDS * channels];
        this.ringSecond = new long[SECONDS];
        java.util.Arrays.fill(ringSecond, Long.MIN_VALUE);
        this.snapshot = new Snapshot(new long[channels], new long[channels], 0, 0);
    }

    /** Closes the running second when {@code nowMs} is in a later one; publishes a new snapshot then. */
    void roll(long nowMs) {
        long second = Math.floorDiv(nowMs, 1000L);
        if (currentSecond == Long.MIN_VALUE) {
            currentSecond = second;
            firstSecond = second;
            return;
        }
        if (second == currentSecond) {
            return;
        }
        int slot = (int) Math.floorMod(currentSecond, (long) SECONDS);
        ringSecond[slot] = currentSecond;
        System.arraycopy(pending, 0, ring, slot * channels, channels);
        java.util.Arrays.fill(pending, 0);
        currentSecond = second;
        publish(second);
    }

    private void publish(long nowSecond) {
        long[] five = new long[channels];
        long[] sixty = new long[channels];
        for (int s = 0; s < SECONDS; s++) {
            long age = nowSecond - ringSecond[s];
            if (ringSecond[s] == Long.MIN_VALUE || age < 1 || age > SECONDS) {
                continue;
            }
            for (int c = 0; c < channels; c++) {
                long v = ring[s * channels + c];
                sixty[c] += v;
                if (age <= 5) {
                    five[c] += v;
                }
            }
        }
        long seen = nowSecond - firstSecond;
        snapshot = new Snapshot(five, sixty, Math.min(5, seen), Math.min(SECONDS, seen));
    }

    Snapshot snapshot() {
        return snapshot;
    }

    /** Forgets everything; owner thread. */
    void reset() {
        java.util.Arrays.fill(pending, 0);
        java.util.Arrays.fill(ring, 0);
        java.util.Arrays.fill(ringSecond, Long.MIN_VALUE);
        currentSecond = Long.MIN_VALUE;
        firstSecond = Long.MIN_VALUE;
        snapshot = new Snapshot(new long[channels], new long[channels], 0, 0);
    }
}
