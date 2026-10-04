/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.pathfinding;

/**
 * When a plain A* search hands over to the hybrid planner. Pure arithmetic, tested on its own.
 *
 * <p><b>Budget.</b> {@code clamp(base + perBlock * d, MIN_MS, MAX_MS)} with {@code d} the straight
 * horizontal distance start→goal: a long route is allowed a longer plain search before it is called
 * slow. Defaults 150 + 3·d, clamped to 250..2000 ms - about 250 ms at 30 blocks, 510 ms at 120,
 * 2 s from 600 on.
 *
 * <p><b>Early switch.</b> Waiting out the budget is pointless when the search is visibly crawling.
 * After each tick, progress is how much of the start distance the search's closest node has closed;
 * extrapolating linearly, the search would finish at {@code elapsed / progress}. More than twice the
 * budget away means switch now. Nothing is extrapolated from the first {@link #MIN_SAMPLE_MS}: the
 * first ticks are dominated by setup and say nothing about the rate.
 */
final class SwitchBudget {

    static final long MIN_MS = 250;
    static final long MAX_MS = 2_000;

    /** Below this much elapsed time the extrapolation is noise. */
    static final long MIN_SAMPLE_MS = 100;

    private SwitchBudget() {
    }

    /** The plain-A* budget for a route of straight-line length {@code distance} blocks. */
    static long budgetMs(double distance, int baseMs, int perBlockMs) {
        double raw = baseMs + (double) perBlockMs * Math.max(0, distance);
        return Math.max(MIN_MS, Math.min(MAX_MS, Math.round(raw)));
    }

    /** Share of the start distance closed so far, 0..1. */
    static double progress(double startDistance, double remaining) {
        if (startDistance <= 0) {
            return 1;
        }
        return Math.max(0, Math.min(1, (startDistance - remaining) / startDistance));
    }

    /**
     * Whether to switch now: the budget is spent, or the extrapolated finish lies beyond twice it.
     * No progress at all after the sample window extrapolates to "never", which also switches.
     */
    static boolean shouldSwitch(long elapsedMs, long budgetMs, double startDistance, double remaining) {
        if (elapsedMs >= budgetMs) {
            return true;
        }
        if (elapsedMs < MIN_SAMPLE_MS) {
            return false;
        }
        double done = progress(startDistance, remaining);
        if (done <= 0) {
            return true;
        }
        return elapsedMs / done > 2.0 * budgetMs;
    }
}
