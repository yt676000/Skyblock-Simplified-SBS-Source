/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.reminder.logic;

import sbs.modid.client.helper.timers.ServerWorldTime;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * A lobby's day as a number with a fraction, and how long the cutoff is away in real time.
 *
 * <p>The day is F3's: {@link ServerWorldTime#f3Day} for the whole part, the same clock divided out
 * for the fraction. The fraction is <b>floored</b> to a tenth, never rounded, so the whole part
 * shown is always the number F3 shows - 17.96 reads "17.9", not "18.0".
 *
 * <p>The real-time part is measured rather than assumed. A lobby day is 20 real minutes only at 20
 * ticks per second with the clock running at rate 1; a lagging lobby takes longer, and a lobby whose
 * day-night cycle is stopped never gets there. {@link Rate} measures clock ticks per real second
 * over the last minute of packets, which folds both effects into one number.
 *
 * <p>Pure: no client state, so the arithmetic is testable on its own.
 */
public final class HollowsLobbyClock {

    /** Ticks per second at a healthy server - the fallback until a rate has been measured. */
    public static final double NOMINAL_TICKS_PER_SECOND = 20.0;

    /** Clock ticks in one minute of clock at rate 1 - the unit of the lead-time setting. */
    public static final long TICKS_PER_CLOCK_MINUTE = 1_200L;

    private HollowsLobbyClock() {
    }

    /** {@code "17.4"}: F3's day and the tenths after it, floored. Only meaningful for {@code ticks >= 0}. */
    public static String dayText(long ticks) {
        long tenths = Math.max(0L, ticks) / (ServerWorldTime.DAY_TICKS / 10L);
        return (tenths / 10L) + "." + (tenths % 10L);
    }

    /** The clock position the cutoff day starts at. */
    public static long closeAtTicks(int closeDay) {
        return closeDay * ServerWorldTime.DAY_TICKS;
    }

    /**
     * Real seconds until the clock reaches {@code closeDay}, at {@code ticksPerSecond} clock ticks per
     * real second; {@code 0} once it has, {@code -1} when the clock is not advancing (so there is no
     * honest estimate).
     */
    public static long secondsUntil(long ticks, int closeDay, double ticksPerSecond) {
        long remaining = closeAtTicks(closeDay) - ticks;
        if (remaining <= 0L) {
            return 0L;
        }
        if (!(ticksPerSecond > 0.0)) {
            return -1L;
        }
        return (long) Math.ceil(remaining / ticksPerSecond);
    }

    /**
     * {@code "~32 min"}, {@code "~1 h 05 min"}, {@code "<1 min"}, or {@code "?"} for no estimate.
     * Minutes are rounded up: "~1 min" while 20 seconds are left reads better than "~0 min".
     */
    public static String etaText(long seconds) {
        if (seconds < 0L) {
            return "?";
        }
        if (seconds < 60L) {
            return "<1 min";
        }
        long minutes = (seconds + 59L) / 60L;
        if (minutes < 60L) {
            return "~" + minutes + " min";
        }
        return String.format(Locale.ROOT, "~%d h %02d min", minutes / 60L, minutes % 60L);
    }

    /**
     * The rate to estimate with: the measured one when there is one, else the nominal 20 ticks per
     * second scaled by the clock's own rate as the server sent it ({@code NaN} = never sent, read as 1).
     */
    public static double effectiveTicksPerSecond(double measured, float clockRate) {
        if (!Double.isNaN(measured)) {
            return measured;
        }
        return Float.isNaN(clockRate) ? NOMINAL_TICKS_PER_SECOND : NOMINAL_TICKS_PER_SECOND * clockRate;
    }

    /**
     * Clock ticks per real second over a sliding window of packet arrivals - and game ticks per real
     * second beside it, the server's tick rate, which is logged but not used for the estimate.
     *
     * <p>Fed one sample per packet with the packet's own arrival time, not the time a tick happened
     * to look, so the jitter of the client's tick does not enter the measurement. A clock that goes
     * backwards (a different lobby, a server that set its time) starts the window over: a span across
     * that jump would measure nothing real.
     */
    public static final class Rate {

        /** How much history the rate is taken over. */
        public static final long WINDOW_MS = 60_000L;
        /** Below this span there is no measurement yet, only the nominal fallback. */
        public static final long MIN_SPAN_MS = 10_000L;

        /** {wallMs, clockTicks, gameTime}, oldest first. */
        private final ArrayDeque<long[]> samples = new ArrayDeque<>();

        public void add(long wallMs, long clockTicks, long gameTime) {
            long[] last = samples.peekLast();
            if (last != null) {
                if (wallMs <= last[0]) {
                    return;
                }
                if (clockTicks < last[1] || gameTime < last[2]) {
                    samples.clear();
                }
            }
            samples.addLast(new long[] {wallMs, clockTicks, gameTime});
            while (samples.size() > 2 && wallMs - samples.peekFirst()[0] > WINDOW_MS) {
                samples.removeFirst();
            }
        }

        public void clear() {
            samples.clear();
        }

        /** Clock ticks per real second, or {@code NaN} before {@link #MIN_SPAN_MS} of samples. */
        public double clockTicksPerSecond() {
            return perSecond(1);
        }

        /** Game ticks per real second (the server's TPS), or {@code NaN} before enough samples. */
        public double gameTicksPerSecond() {
            return perSecond(2);
        }

        private double perSecond(int column) {
            if (samples.size() < 2) {
                return Double.NaN;
            }
            long[] first = samples.peekFirst();
            long[] last = samples.peekLast();
            long span = last[0] - first[0];
            if (span < MIN_SPAN_MS) {
                return Double.NaN;
            }
            return (last[column] - first[column]) * 1000.0 / span;
        }
    }
}
