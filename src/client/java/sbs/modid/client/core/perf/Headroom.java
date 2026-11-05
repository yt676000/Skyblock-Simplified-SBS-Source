/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.perf;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import sbs.modid.client.core.config.ConfigManager;

/**
 * How much spare capacity this machine has right now, relative to its own state rather than to a
 * fixed number of milliseconds - for work that may wait (filling caches, saving files).
 *
 * <p>Not a second budget: it reads the rolling figures {@link Perf} already keeps, and the
 * {@code tickBudgetMs} budget and its shedding stay what they are, used here as a hard cap. What
 * this adds is the relative view: a fast machine whose SBS cost is 0.2 ms per tick is far inside
 * any absolute budget, yet should still back off when its frames get tight or SBS's share of the
 * frame spikes above its own normal; a slow machine whose frames never reach the target should
 * still make some progress while SBS itself is idle.
 *
 * <p>Client thread only. {@link #fraction()} is recomputed at most once a second.
 */
public final class Headroom {

    /** Frames count as having headroom while the p95 frame time is at most this share of the target. */
    static final double FRAME_HEADROOM = 0.8;
    /** Frame target when neither a limit nor vsync sets one. */
    static final double DEFAULT_TARGET_FPS = 60;
    /** A share spike: above this multiple of the baseline... */
    static final double SPIKE_FACTOR = 2.0;
    /** ...and at least this many percentage points above it (tiny baselines are noise). */
    static final double SPIKE_MIN_POINTS = 5.0;
    /** One Minecraft tick. */
    static final double TICK_MS = 50.0;
    /** Fraction granted to a machine without frame headroom while SBS itself is quiet. */
    static final double IDLE_TRICKLE = 0.05;
    /** Baseline smoothing per one-second update (about a minute to follow a lasting change). */
    private static final double BASELINE_ALPHA = 0.02;

    private static long computedSecond = Long.MIN_VALUE;
    private static double fraction = 1.0;
    private static double baselineShare = -1;
    private static String reason = "";

    private Headroom() {
    }

    /**
     * The spare capacity as a fraction in {@code [0, 1]}: 0 = do nothing now, 1 = plenty. Deferrable
     * work sizes its slice by it.
     */
    public static double fraction() {
        long second = System.currentTimeMillis() / 1000;
        if (second != computedSecond) {
            computedSecond = second;
            recompute();
        }
        return fraction;
    }

    /** Why the last {@link #fraction} was what it was, for {@code /sbs perf}. */
    public static String reason() {
        return reason;
    }

    private static void recompute() {
        RollingStats.Summary frameTime = Perf.frameTime(5);
        RollingStats.Summary frameTotal = Perf.frameTotal(5);
        RollingStats.Summary tick = Perf.tickTotal(5);
        double share = frameTime.avgMs() > 0 ? frameTotal.avgMs() / frameTime.avgMs() * 100 : 0;
        double budget = ConfigManager.getInstance().get().performance.tickBudgetMs;
        Decision decision = decide(frameTime.p95Ms(), targetFrameMs(), share, baselineShare,
                tick.p95Ms(), budget, Perf.shedding());
        fraction = decision.fraction();
        reason = decision.reason();
        // The baseline follows the share only outside a spike, so a spike cannot become the norm.
        if (!decision.spike() && frameTime.avgMs() > 0) {
            baselineShare = baselineShare < 0 ? share : baselineShare + (share - baselineShare) * BASELINE_ALPHA;
        }
    }

    /** Target frame time: the FPS limit, else the refresh rate under vsync, else 60 FPS. */
    static double targetFrameMs() {
        Minecraft mc = Minecraft.getInstance();
        Options options = mc.options;
        int limit = options.framerateLimit().get();
        double fps;
        if (limit > 0 && limit < Options.UNLIMITED_FRAMERATE_CUTOFF) {
            fps = limit;
        } else if (options.enableVsync().get() && mc.getWindow().getRefreshRate() > 0) {
            fps = mc.getWindow().getRefreshRate();
        } else {
            fps = DEFAULT_TARGET_FPS;
        }
        return 1000.0 / fps;
    }

    /** One evaluation, with whether the share was a spike (which freezes the baseline). */
    record Decision(double fraction, boolean spike, String reason) {
    }

    /**
     * The rule, pure so it is unit-tested.
     *
     * <ol>
     *   <li>Shedding, or tick p95 over the absolute budget: 0. The absolute budget is the hard cap
     *       that stays in force on fast machines.</li>
     *   <li>SBS's share of the frame more than {@link #SPIKE_FACTOR} times and {@link #SPIKE_MIN_POINTS}
     *       points above its own baseline: 0.</li>
     *   <li>Otherwise the smaller of the frame headroom ({@code 1 - p95/(0.8 * target)}) and the tick
     *       headroom ({@code 1 - tickP95/budget}, and never more than {@code 1 - tickP95/50 ms}).</li>
     *   <li>No frame headroom while SBS's tick cost is under a quarter of the budget: a trickle of
     *       {@link #IDLE_TRICKLE}, so a machine that never reaches its frame target still progresses
     *       when SBS is idle.</li>
     * </ol>
     */
    static Decision decide(double frameP95Ms, double targetFrameMs, double share, double baselineShare,
                           double tickP95Ms, double tickBudgetMs, boolean shedding) {
        if (shedding) {
            return new Decision(0, false, "shedding");
        }
        if (tickBudgetMs > 0 && tickP95Ms > tickBudgetMs) {
            return new Decision(0, false, "tick over budget");
        }
        if (baselineShare >= 0 && share > baselineShare * SPIKE_FACTOR && share > baselineShare + SPIKE_MIN_POINTS) {
            return new Decision(0, true, "SBS share spike");
        }
        double tickHeadroom = 1 - tickP95Ms / TICK_MS;
        if (tickBudgetMs > 0) {
            tickHeadroom = Math.min(tickHeadroom, 1 - tickP95Ms / tickBudgetMs);
        }
        double frameHeadroom = frameP95Ms <= 0 || targetFrameMs <= 0
                ? 1
                : 1 - frameP95Ms / (FRAME_HEADROOM * targetFrameMs);
        if (frameHeadroom <= 0) {
            boolean quiet = tickBudgetMs <= 0 ? tickP95Ms < TICK_MS / 20 : tickP95Ms < tickBudgetMs / 4;
            return quiet
                    ? new Decision(IDLE_TRICKLE, false, "frames tight, SBS idle: trickle")
                    : new Decision(0, false, "frames tight");
        }
        double result = Math.max(0, Math.min(1, Math.min(frameHeadroom, tickHeadroom)));
        return new Decision(result, false, result > 0 ? "headroom" : "tick headroom used");
    }
}
