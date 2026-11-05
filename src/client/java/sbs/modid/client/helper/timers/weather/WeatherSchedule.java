/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.timers.weather;

/**
 * The island weather clock, as pure arithmetic on UTC epoch seconds - no game state, no wall clock of
 * its own, so the tests can drive every edge.
 *
 * <p>Hypixel 0.27.2 runs <b>one weather per real hour, XX:35 to XX:55</b>, and every third one is
 * EXTREME. The minute is taken in UTC: XX:35 is the same instant in every whole-hour time zone, and
 * UTC keeps the half-hour zones honest too.
 *
 * <p>The 3-hour cycle's <b>phase is not published</b>, so it is learned: {@code phase} is
 * {@code floorMod(hourIndex, 3)} of an hour whose weather was seen to be extreme, where
 * {@code hourIndex} is UTC epoch hours. {@code -1} means not known yet, and every extreme answer is then
 * {@code -1} rather than a guess.
 */
public final class WeatherSchedule {

    public static final int START_MINUTE = 35;
    public static final int END_MINUTE = 55;
    public static final int EXTREME_EVERY = 3;
    private static final long HOUR = 3_600L;
    private static final long START_OFFSET = START_MINUTE * 60L;
    private static final long END_OFFSET = END_MINUTE * 60L;

    private WeatherSchedule() {
    }

    /** UTC epoch hours of {@code epochSeconds}. */
    public static long hourIndex(long epochSeconds) {
        return Math.floorDiv(epochSeconds, HOUR);
    }

    /** True from XX:35:00 (inclusive) to XX:55:00 (exclusive). */
    public static boolean active(long epochSeconds) {
        long inHour = Math.floorMod(epochSeconds, HOUR);
        return inHour >= START_OFFSET && inHour < END_OFFSET;
    }

    /** Epoch second the next weather starts; while one is running, the one after it. */
    public static long nextStart(long epochSeconds) {
        long hourStart = hourIndex(epochSeconds) * HOUR;
        long start = hourStart + START_OFFSET;
        return epochSeconds < start ? start : start + HOUR;
    }

    /** Epoch second the running weather ends, or {@code -1} while none is running. */
    public static long currentEnd(long epochSeconds) {
        return active(epochSeconds) ? hourIndex(epochSeconds) * HOUR + END_OFFSET : -1L;
    }

    /** Whether the weather of hour {@code hourIndex} is extreme; false while the phase is unknown. */
    public static boolean extremeHour(long hourIndex, int phase) {
        return phase >= 0 && Math.floorMod(hourIndex, EXTREME_EVERY) == phase;
    }

    /** The phase an extreme weather seen at {@code epochSeconds} pins. */
    public static int phaseOf(long epochSeconds) {
        return (int) Math.floorMod(hourIndex(epochSeconds), (long) EXTREME_EVERY);
    }

    /**
     * Epoch second the next extreme weather starts (the running one excluded), or {@code -1} while the
     * phase is unknown.
     */
    public static long nextExtremeStart(long epochSeconds, int phase) {
        if (phase < 0) {
            return -1L;
        }
        long start = nextStart(epochSeconds);
        while (!extremeHour(hourIndex(start), phase)) {
            start += HOUR;
        }
        return start;
    }
}
