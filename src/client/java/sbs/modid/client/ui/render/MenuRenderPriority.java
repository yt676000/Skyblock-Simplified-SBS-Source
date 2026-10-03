/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.render;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.dev.DevMode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The mod's priority list for what it draws over a container screen, and the frame budget that
 * enforces it.
 *
 * <p><b>The order is Minecraft, then the part of the mod the open menu is about, then everything
 * else.</b> {@link RenderTier} states it in full. Vanilla is first and is not scheduled here at all
 * - the mod's passes run inside hooks vanilla has already returned from, so giving way to the game
 * means never spending frame time the game still needs, which is what shedding the third tier does.
 *
 * <p><b>Why a budget and not a switch.</b> On a quiet frame there is nothing to gain by skipping a
 * pass that costs twenty microseconds, and a mechanism that fires when it is not needed is a
 * mechanism whose effects nobody can predict. So the third tier is dropped only while the mod's own
 * per-frame cost is over {@link #BUDGET_NS}: below that everything runs and this class is a pair of
 * {@code nanoTime} calls. The Bazaar is what prompted it - a Bazaar page is a menu where a dozen
 * features have nothing to say and every one of them was paying to find that out, per frame.
 *
 * <p><b>A background pass is deferred, never abandoned.</b> Any pass skipped for the budget still
 * runs at least every {@link #HEARTBEAT_MS}. That is the safety net under the tier declarations: a
 * pass wrongly marked {@link RenderTier#BACKGROUND} that really was drawing something degrades to
 * updating four times a second - visible, reportable and fixable - instead of vanishing. Silent
 * disappearance is the failure this project has already paid for elsewhere, and it is not worth the
 * handful of microseconds that skipping outright would save.
 *
 * <p><b>The window is one frame, offset by one hook.</b> {@link #beginFrame} is called from the
 * overlay pass, which runs once per frame per container screen; the slot decorations of the next
 * frame arrive before the next {@code beginFrame} and are counted in the same window. The total is
 * therefore one frame's worth of the mod's container drawing, just not on the boundary vanilla
 * would draw. Nothing here needs it to be.
 *
 * <p><b>If {@link #beginFrame} is never called, this does nothing</b> - {@code shedding} stays false
 * and every pass runs, which is the behaviour that existed before this class. That is the deliberate
 * direction to fail in.
 *
 * <p>Render thread only, for the same reason as {@link MenuFrame}.
 */
public final class MenuRenderPriority {

    /**
     * What the mod may spend drawing over a menu before the third tier is shed, in nanoseconds.
     *
     * <p>3ms of a 16.6ms frame. Under it the mod is not what is making the menu slow and there is
     * nothing to gain by dropping anything; over it the mod is a measurable part of the frame, and
     * the part worth dropping first is the part with nothing to draw. <b>Not derived from a real
     * profile</b> - it is a threshold picked to sit well above an idle menu and well below a frame
     * anybody would call laggy.
     */
    private static final long BUDGET_NS = 3_000_000L;

    /**
     * Shedding stops again below this. The gap to {@link #BUDGET_NS} is hysteresis: without it a
     * menu costing exactly the budget would shed on one frame, come in under on the next because it
     * had shed, and oscillate - so a third of the mod's overlays would flicker at half the frame
     * rate.
     */
    private static final long RESUME_NS = 2_000_000L;

    /** How long a background pass may be skipped before it is run regardless of the budget. */
    private static final long HEARTBEAT_MS = 250L;

    /** How often the developer-mode report is printed. */
    private static final long REPORT_INTERVAL_MS = 5_000L;

    /** Weight of the running average, out of {@code SMOOTHING + 1}. Five frames to react. */
    private static final int SMOOTHING = 4;

    /** What the mod has spent in the current window. */
    private static long spentNs;

    /** The running average of the last few windows - the number the budget is judged against. */
    private static long smoothedNs;

    /** Whether the third tier is currently being dropped. */
    private static boolean shedding;

    /** Start of the current window, and the clock every heartbeat is measured against. */
    private static long windowStartedAtMs;

    /** The screen the current average describes; a different menu starts the measurement over. */
    private static Object lastScreen;

    /** When each background pass last actually ran, for the heartbeat. */
    private static final Map<String, Long> lastRunAtMs = new HashMap<>();

    /** Per-pass running cost. Developer mode only - it is the report, not an input to a decision. */
    private static final Map<String, Long> costNs = new HashMap<>();

    private static long lastReportAtMs;
    private static int skippedThisWindow;

    private MenuRenderPriority() {
    }

    /**
     * Closes the previous window and opens a new one. Called once per frame, from the overlay pass,
     * before any {@link #run} for that frame.
     */
    public static void beginFrame(MenuFrame frame) {
        long now = System.currentTimeMillis();
        Object screen = frame == null ? null : frame.screen();
        if (screen != lastScreen) {
            // A different menu is a different cost. Carrying the old average over would judge this
            // menu by the last one's frames - and the menu just left is often the heavy one.
            lastScreen = screen;
            smoothedNs = 0;
            shedding = false;
            lastRunAtMs.clear();
            costNs.clear();
        } else {
            smoothedNs = smoothedNs == 0
                    ? spentNs
                    : (smoothedNs * SMOOTHING + spentNs) / (SMOOTHING + 1);
            if (smoothedNs > BUDGET_NS) {
                shedding = true;
            } else if (smoothedNs < RESUME_NS) {
                shedding = false;
            }
        }
        report(now);
        spentNs = 0;
        skippedThisWindow = 0;
        windowStartedAtMs = now;
    }

    /**
     * Runs one of the mod's drawing passes, unless it is {@link RenderTier#BACKGROUND} and the frame
     * cannot afford it.
     *
     * <p>Whatever the pass throws is passed on unchanged - this measures work, it does not decide
     * what a failure means. Callers that need a guard (the slot decorators do) keep their own around
     * this call.
     *
     * @param id   stable identifier, for the heartbeat and the developer report. Never display text.
     * @param tier whether this pass is about the menu that is open - see {@link RenderTier}
     * @param pass the drawing itself
     */
    public static void run(String id, RenderTier tier, Runnable pass) {
        if (!allow(id, tier)) {
            return;
        }
        long start = System.nanoTime();
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.menu(id)) {
            pass.run();
        } finally {
            long cost = System.nanoTime() - start;
            spentNs += cost;
            if (tier == RenderTier.BACKGROUND) {
                lastRunAtMs.put(id, windowStartedAtMs);
            }
            if (DevMode.ACTIVE) {
                Long previous = costNs.get(id);
                costNs.put(id, previous == null
                        ? cost
                        : (previous * SMOOTHING + cost) / (SMOOTHING + 1));
            }
        }
    }

    /** Whether the frame is currently deferring background passes. Diagnostics only. */
    public static boolean shedding() {
        return shedding;
    }

    private static boolean allow(String id, RenderTier tier) {
        if (tier != RenderTier.BACKGROUND || !shedding) {
            return true;
        }
        Long last = lastRunAtMs.get(id);
        if (last == null || windowStartedAtMs - last >= HEARTBEAT_MS) {
            return true;   // the heartbeat: deferred, never abandoned
        }
        skippedThisWindow++;
        return false;
    }

    /**
     * One line naming the frame cost, whether the mod is shedding, and the three passes that cost
     * the most - which is the question anyone opening this file is actually asking.
     */
    private static void report(long now) {
        if (!DevMode.ACTIVE || now - lastReportAtMs < REPORT_INTERVAL_MS) {
            return;
        }
        lastReportAtMs = now;
        List<Map.Entry<String, Long>> slowest = costNs.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .toList();
        StringBuilder detail = new StringBuilder();
        for (Map.Entry<String, Long> entry : slowest) {
            if (!detail.isEmpty()) {
                detail.append(", ");
            }
            detail.append(entry.getKey()).append(' ').append(millis(entry.getValue()));
        }
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Render] container frame {} ({}), {} pass(es) deferred - slowest: {}",
                millis(smoothedNs),
                shedding ? "over budget, deferring background passes" : "within budget",
                skippedThisWindow,
                detail.isEmpty() ? "nothing measured yet" : detail);
    }

    private static String millis(long nanos) {
        return String.format("%.2fms", nanos / 1_000_000.0);
    }
}
