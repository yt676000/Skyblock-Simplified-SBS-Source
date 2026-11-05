/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.sound;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Drops identical sounds started too often at the same place - ability spam that otherwise fills
 * the engine's channel pool ("Maximum sound pool size reached") and starves every other sound.
 *
 * <p>A sound is refused when at least {@code maxSounds} instances with the same key already started
 * within the window and within {@code radius} blocks of it. Refused sounds are not recorded, so a
 * steady stream keeps playing at the allowed rate instead of being muted outright.
 *
 * <p><b>Hot path, client thread only.</b> Each key owns one fixed-size ring of start times and
 * positions, allocated the first time that key is heard; after that a query is one hash lookup and
 * a scan of {@link #CAPACITY} slots, with no allocation. The ring is the counting window itself,
 * not an optimisation, so the performance rules' §6 ban on unproven ring buffers does not apply.
 * Keys are sound ids, a finite set, and {@link #clear()} runs on every config change and world
 * change.
 *
 * <p>Time is passed in by the caller in nanoseconds so the window can be tested without a clock.
 */
public final class SoundSpamLimiter {

    /** Most instances one key remembers - and so the highest {@code maxSounds} that means anything. */
    public static final int CAPACITY = 16;

    private static final long NANOS_PER_TICK = 50_000_000L;

    private final Map<Object, Ring> rings = new HashMap<>();

    private int maxSounds = 3;
    private long windowNanos = 2 * NANOS_PER_TICK;
    private double radiusSq = 25.0;

    /** One key's recent starts. Unfilled slots have {@code time == Long.MIN_VALUE}. */
    private static final class Ring {
        final long[] time = new long[CAPACITY];
        final double[] x = new double[CAPACITY];
        final double[] y = new double[CAPACITY];
        final double[] z = new double[CAPACITY];
        int head;

        Ring() {
            Arrays.fill(time, Long.MIN_VALUE);
        }
    }

    /** Sets the thresholds; values are clamped to what the ring can represent. */
    public void configure(int maxSounds, int windowTicks, int radiusBlocks) {
        this.maxSounds = Math.max(1, Math.min(CAPACITY, maxSounds));
        this.windowNanos = Math.max(1, windowTicks) * NANOS_PER_TICK;
        double radius = Math.max(0, radiusBlocks);
        this.radiusSq = radius * radius;
    }

    /** Forgets every recorded start. */
    public void clear() {
        rings.clear();
    }

    /**
     * Whether a sound with this key, starting now at this position, may play. A {@code true}
     * answer records the start.
     */
    public boolean allow(Object key, double x, double y, double z, long nowNanos) {
        Ring ring = rings.get(key);
        if (ring == null) {
            ring = new Ring();
            rings.put(key, ring);
        }
        int recent = 0;
        for (int i = 0; i < CAPACITY; i++) {
            long t = ring.time[i];
            if (t == Long.MIN_VALUE || nowNanos - t >= windowNanos) {
                continue;
            }
            double dx = ring.x[i] - x;
            double dy = ring.y[i] - y;
            double dz = ring.z[i] - z;
            if (dx * dx + dy * dy + dz * dz <= radiusSq && ++recent >= maxSounds) {
                return false;
            }
        }
        int slot = ring.head;
        ring.time[slot] = nowNanos;
        ring.x[slot] = x;
        ring.y[slot] = y;
        ring.z[slot] = z;
        ring.head = (slot + 1) % CAPACITY;
        return true;
    }
}
