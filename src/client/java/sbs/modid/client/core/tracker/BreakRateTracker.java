/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.tracker;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Blocks broken per second, for any set of blocks: the caller decides what counts and hands each
 * one to {@link #record}. Farming feeds it crops; a mining HUD can feed ores to a second instance
 * with nothing changed here.
 *
 * <p>Pure and clock-free - every method takes {@code nowMs} - so the maths is unit-tested without a
 * game. Not thread-safe; use it from the render thread.
 *
 * <ul>
 *   <li><b>Rates</b>: breaks inside the last 1 s and 5 s. While a run is younger than the window,
 *       the window shrinks to the run's age (never under 1 s), so the 5 s value is not dragged down
 *       by the seconds before you started.</li>
 *   <li><b>Paused</b>: nothing broken for {@value #PAUSE_MS} ms. The next break starts a new run.</li>
 *   <li><b>Usual speed</b>: the median of the 5 s rate, sampled once a second while running, leaving
 *       out the first {@value #WARMUP_MS} ms of each run. A median, so a lag spike or a lane change
 *       does not move it. Needs {@value #MIN_USUAL_SAMPLES} samples before it exists.</li>
 *   <li><b>Drop</b>: the 5 s rate under a fraction of the usual speed for {@value #DROP_MS} ms
 *       straight. Reported once; re-armed when the rate recovers or a pause ends the run.</li>
 *   <li><b>Session</b>: a gap longer than {@value #SESSION_GAP_MS} ms forgets the usual speed and
 *       the peak - that is having stopped farming, not a pause.</li>
 * </ul>
 */
public final class BreakRateTracker {

    public static final long PAUSE_MS = 3_000L;
    public static final long SHORT_MS = 1_000L;
    public static final long LONG_MS = 5_000L;
    public static final long WARMUP_MS = 5_000L;
    public static final long DROP_MS = 3_000L;
    public static final long SESSION_GAP_MS = 10 * 60_000L;
    public static final int MIN_USUAL_SAMPLES = 10;
    public static final int GRAPH_SECONDS = 60;
    /** An hour of samples; older ones are overwritten, so the median follows the last hour. */
    static final int MAX_SAMPLES = 3_600;

    private static final long NEVER = Long.MIN_VALUE;

    private final ArrayDeque<Long> recent = new ArrayDeque<>();
    private long lastBreak = NEVER;
    private long runStart = NEVER;
    private long lastSample = NEVER;
    private double peak;

    private final double[] samples = new double[MAX_SAMPLES];
    private int sampleCount;
    private int sampleNext;
    private double usualCache = -1;
    private boolean usualDirty;

    private final double[] graph = new double[GRAPH_SECONDS];
    private int graphNext;

    private long belowSince = NEVER;
    private boolean warned;

    /** One counted block broken at {@code nowMs}. */
    public void record(long nowMs) {
        if (lastBreak == NEVER || nowMs - lastBreak > SESSION_GAP_MS) {
            resetSession();
        }
        if (paused(nowMs)) {
            runStart = nowMs;
            belowSince = NEVER;
            warned = false;
        }
        lastBreak = nowMs;
        recent.addLast(nowMs);
        trim(nowMs);
    }

    /** Whether nothing has been broken for {@link #PAUSE_MS} (or ever). */
    public boolean paused(long nowMs) {
        return lastBreak == NEVER || nowMs - lastBreak > PAUSE_MS;
    }

    /** Whether anything has been counted since the session began. */
    public boolean everRecorded() {
        return lastBreak != NEVER;
    }

    /** Whether anything was broken in the last {@code withinMs}. */
    public boolean seenWithin(long nowMs, long withinMs) {
        return lastBreak != NEVER && nowMs - lastBreak <= withinMs;
    }

    /** Breaks per second over the last second; 0 while paused. */
    public double shortRate(long nowMs) {
        return rate(nowMs, SHORT_MS);
    }

    /** Breaks per second over the last five seconds (less for a young run); 0 while paused. */
    public double longRate(long nowMs) {
        return rate(nowMs, LONG_MS);
    }

    double rate(long nowMs, long windowMs) {
        if (paused(nowMs)) {
            return 0;
        }
        trim(nowMs);
        long window = Math.min(windowMs, Math.max(SHORT_MS, nowMs - runStart));
        // A window shrunk to the run's age starts exactly on the run's first break, which a
        // half-open window would drop - reading every young run one break low.
        boolean ageLimited = window < windowMs;
        int count = 0;
        for (Iterator<Long> it = recent.descendingIterator(); it.hasNext(); ) {
            long age = nowMs - it.next();
            if (age < window || (ageLimited && age == window)) {
                count++;
            } else {
                break;
            }
        }
        return count * 1000.0 / window;
    }

    /**
     * Advances the per-second bookkeeping: graph, peak and usual-speed samples. Call it often (every
     * tick is fine); it does work at most once a second.
     */
    public void tick(long nowMs) {
        if (lastSample != NEVER && nowMs - lastSample < 1_000L) {
            return;
        }
        lastSample = nowMs;
        double oneSecond = shortRate(nowMs);
        graph[graphNext] = oneSecond;
        graphNext = (graphNext + 1) % GRAPH_SECONDS;
        if (paused(nowMs) || nowMs - runStart < WARMUP_MS) {
            return;
        }
        peak = Math.max(peak, oneSecond);
        samples[sampleNext] = longRate(nowMs);
        sampleNext = (sampleNext + 1) % MAX_SAMPLES;
        sampleCount = Math.min(sampleCount + 1, MAX_SAMPLES);
        usualDirty = true;
    }

    /** The median 5 s rate of this session's running time, or -1 until there is enough of it. */
    public double usual() {
        if (sampleCount < MIN_USUAL_SAMPLES) {
            return -1;
        }
        if (usualDirty) {
            double[] sorted = Arrays.copyOf(samples, sampleCount);
            Arrays.sort(sorted);
            int mid = sorted.length / 2;
            usualCache = sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
            usualDirty = false;
        }
        return usualCache;
    }

    /** Highest one-second rate this session, outside warm-up. */
    public double peak() {
        return peak;
    }

    /** The last {@link #GRAPH_SECONDS} one-second rates, oldest first. */
    public double[] graph() {
        double[] out = new double[GRAPH_SECONDS];
        for (int i = 0; i < GRAPH_SECONDS; i++) {
            out[i] = graph[(graphNext + i) % GRAPH_SECONDS];
        }
        return out;
    }

    /**
     * True exactly once per drop: the 5 s rate has stayed under {@code fraction} of the usual speed
     * for {@link #DROP_MS}. Never while paused, in warm-up, or before a usual speed exists.
     */
    public boolean checkDrop(long nowMs, double fraction) {
        double usual = usual();
        if (paused(nowMs) || usual <= 0 || nowMs - runStart < WARMUP_MS) {
            belowSince = NEVER;
            return false;
        }
        if (longRate(nowMs) >= usual * fraction) {
            belowSince = NEVER;
            warned = false;
            return false;
        }
        if (belowSince == NEVER) {
            belowSince = nowMs;
        }
        if (!warned && nowMs - belowSince >= DROP_MS) {
            warned = true;
            return true;
        }
        return false;
    }

    /** Forgets everything. */
    public void resetSession() {
        recent.clear();
        lastBreak = NEVER;
        runStart = NEVER;
        lastSample = NEVER;
        peak = 0;
        sampleCount = 0;
        sampleNext = 0;
        usualCache = -1;
        usualDirty = false;
        Arrays.fill(graph, 0);
        graphNext = 0;
        belowSince = NEVER;
        warned = false;
    }

    private void trim(long nowMs) {
        while (!recent.isEmpty() && nowMs - recent.peekFirst() >= LONG_MS) {
            recent.pollFirst();
        }
    }
}
