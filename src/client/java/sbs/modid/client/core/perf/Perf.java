/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.perf;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What every SBS feature costs per frame and per tick, and the budget that keeps the total down.
 *
 * <p><b>Use:</b> {@code try (Perf.Section s = Perf.hud("garden.pestHud")) { ... }} around a HUD
 * pass, {@code Perf.tick(...)} around tick work. The section object is created once per name and
 * reused, so a call allocates nothing; with {@code performance.sbsBudget} off every section is a
 * shared no-op. Client thread only - every hook that calls it runs there.
 *
 * <p><b>Windows.</b> {@link #endFrame} (start of the HUD pass) and {@link #endTick} (start of the
 * client tick) close the previous window: each section's time in it becomes one sample of its
 * {@link RollingStats}, and the window's total feeds the {@link TierScheduler}.
 *
 * <p><b>Budget.</b> {@link #allowTick} lets the scheduler defer tick work marked
 * {@link TierScheduler.Tier#NORMAL} or {@link TierScheduler.Tier#BACKGROUND} while SBS's tick
 * total is over budget. HUD drawing is measured but never shed: skipping a card's draw makes it
 * flicker; cards degrade by caching their text (see the spec).
 */
public final class Perf {

    /** Where a section's time is counted. */
    public enum Kind { FRAME, TICK }

    /** One measured section; reused, never allocated per call. */
    public static final class Section implements AutoCloseable {
        final int id;
        long start;

        Section(int id) {
            this.id = id;
        }

        @Override
        public void close() {
            if (id >= 0) {
                long cost = System.nanoTime() - start;
                windowNs[id] += cost;
                windowCalls[id]++;
                if (allocTracking) {
                    allocBytes[id] += Math.max(0, threadAllocated() - allocStart);
                }
            }
        }
    }

    private static final Section NOOP = new Section(-1);
    private static final Map<String, Section> BY_NAME = new HashMap<>();
    private static final List<String> NAMES = new ArrayList<>();
    private static final List<Kind> KINDS = new ArrayList<>();
    private static final List<TierScheduler.Tier> TIERS = new ArrayList<>();
    private static final List<RollingStats> STATS = new ArrayList<>();

    static long[] windowNs = new long[256];
    static int[] windowCalls = new int[256];
    static long[] allocBytes = new long[256];

    /** Totals of SBS work per frame and per tick, as sections of their own. */
    private static final RollingStats FRAME_TOTAL = new RollingStats();
    private static final RollingStats TICK_TOTAL = new RollingStats();
    /** Real frame time (between {@link #endFrame} calls), for the share of SBS in it. */
    private static final RollingStats FRAME_TIME = new RollingStats();

    private static TierScheduler tickScheduler = new TierScheduler(1_000_000L, 250, 1_000);
    private static long lastFrameAt;
    private static boolean wasShedding;
    private static long lastShedLogAt;

    /** Allocation tracking (only while the overlay is open: the MXBean call is not free). */
    static volatile boolean allocTracking;
    private static long allocStart;
    private static final java.lang.management.ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private Perf() {
    }

    private static boolean enabled() {
        return ConfigManager.getInstance().get().performance.sbsBudget;
    }

    // ------------------------------------------------------------------ sections

    /** A HUD / world-render section. */
    public static Section hud(String name) {
        return open(name, Kind.FRAME, TierScheduler.Tier.CRITICAL);
    }

    /** A tick section. */
    public static Section tick(String name) {
        return open(name, Kind.TICK, TierScheduler.Tier.CRITICAL);
    }

    /** A container-menu pass (from {@code MenuRenderPriority}). */
    public static Section menu(String name) {
        return open(name, Kind.FRAME, TierScheduler.Tier.CRITICAL);
    }

    private static Section open(String name, Kind kind, TierScheduler.Tier tier) {
        if (!enabled()) {
            return NOOP;
        }
        Section section = BY_NAME.get(name);
        if (section == null) {
            section = register(name, kind, tier);
        }
        if (allocTracking) {
            allocStart = threadAllocated();
        }
        section.start = System.nanoTime();
        return section;
    }

    /**
     * Whether a tick section may run this tick: CRITICAL always; NORMAL / BACKGROUND only as often as
     * the budget allows while SBS is over it. Declares the section's tier on first use.
     */
    public static boolean allowTick(String name, TierScheduler.Tier tier) {
        if (!enabled()) {
            return true;
        }
        Section section = BY_NAME.get(name);
        if (section == null) {
            section = register(name, Kind.TICK, tier);
        }
        return tickScheduler.allow(section.id, tier, System.currentTimeMillis());
    }

    private static Section register(String name, Kind kind, TierScheduler.Tier tier) {
        int id = NAMES.size();
        Section section = new Section(id);
        BY_NAME.put(name, section);
        NAMES.add(name);
        KINDS.add(kind);
        TIERS.add(tier);
        STATS.add(new RollingStats());
        if (id >= windowNs.length) {
            windowNs = java.util.Arrays.copyOf(windowNs, windowNs.length * 2);
            windowCalls = java.util.Arrays.copyOf(windowCalls, windowCalls.length * 2);
            allocBytes = java.util.Arrays.copyOf(allocBytes, allocBytes.length * 2);
        }
        return section;
    }

    // ------------------------------------------------------------------ windows

    /** Start of a frame's HUD pass: closes the previous frame. */
    public static void endFrame() {
        if (!enabled()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        long nowNs = System.nanoTime();
        if (lastFrameAt != 0) {
            FRAME_TIME.sample(nowNs - lastFrameAt, 1, nowMs);
        }
        lastFrameAt = nowNs;
        FRAME_TOTAL.sample(closeWindow(Kind.FRAME, nowMs), 1, nowMs);
    }

    /** Start of a client tick: closes the previous tick, feeds the budget. */
    public static void endTick() {
        if (!enabled()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        int simulate = ConfigManager.getInstance().get().performance.simulateSlowMs;
        if (simulate > 0) {
            try (Section s = tick("dev.simulateSlow")) {
                sleepQuietly(simulate);
            }
        }
        double budgetMs = ConfigManager.getInstance().get().performance.tickBudgetMs;
        long budgetNs = (long) (Math.max(0.1, budgetMs) * 1_000_000L);
        if (budgetNs != tickBudgetNs) {
            tickBudgetNs = budgetNs;
            tickScheduler = new TierScheduler(budgetNs, 250, 1_000);
        }
        long spent = closeWindow(Kind.TICK, nowMs);
        TICK_TOTAL.sample(spent, 1, nowMs);
        int skipped = tickScheduler.skipped();
        boolean shedding = tickScheduler.endWindow(spent);
        if (shedding != wasShedding || (shedding && nowMs - lastShedLogAt > 10_000L)) {
            wasShedding = shedding;
            lastShedLogAt = nowMs;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Perf] tick work {} ms (budget {} ms) - {}{}",
                    String.format("%.2f", tickScheduler.smoothedNs() / 1e6), budgetMs,
                    shedding ? "deferring NORMAL/BACKGROUND tick work" : "within budget again",
                    shedding ? ", " + skipped + " deferred last tick; slowest: " + slowest(Kind.TICK) : "");
        }
    }

    private static long tickBudgetNs = 1_000_000L;

    private static long closeWindow(Kind kind, long nowMs) {
        long total = 0;
        for (int id = 0; id < NAMES.size(); id++) {
            if (KINDS.get(id) != kind) {
                continue;
            }
            total += windowNs[id];
            STATS.get(id).sample(windowNs[id], windowCalls[id], nowMs);
            windowNs[id] = 0;
            windowCalls[id] = 0;
        }
        return total;
    }

    private static String slowest(Kind kind) {
        String best = "nothing";
        double bestMs = -1;
        long now = System.currentTimeMillis();
        for (int id = 0; id < NAMES.size(); id++) {
            if (KINDS.get(id) == kind) {
                double ms = STATS.get(id).summary(5, now).avgMs();
                if (ms > bestMs) {
                    bestMs = ms;
                    best = NAMES.get(id) + " " + String.format("%.2f", ms) + " ms";
                }
            }
        }
        return best;
    }

    private static void sleepQuietly(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long threadAllocated() {
        if (THREADS instanceof com.sun.management.ThreadMXBean sun) {
            return sun.getCurrentThreadAllocatedBytes();
        }
        return 0;
    }

    // ------------------------------------------------------------------ reading

    /** One row of the report. */
    public record Row(String name, Kind kind, TierScheduler.Tier tier, RollingStats.Summary last5,
                      RollingStats.Summary last60, double allocMbPerSec) {
    }

    /** Every section with its 5 s and 60 s figures, worst p95 (5 s) first. */
    public static List<Row> rows() {
        long now = System.currentTimeMillis();
        List<Row> out = new ArrayList<>();
        for (int id = 0; id < NAMES.size(); id++) {
            RollingStats.Summary five = STATS.get(id).summary(5, now);
            double alloc = allocWindowSec > 0 ? allocBytes[id] / 1e6 / allocWindowSec : 0;
            out.add(new Row(NAMES.get(id), KINDS.get(id), TIERS.get(id), five,
                    STATS.get(id).summary(60, now), alloc));
        }
        out.sort((a, b) -> Double.compare(b.last5().p95Ms(), a.last5().p95Ms()));
        return out;
    }

    public static RollingStats.Summary frameTotal(int windowSec) {
        return FRAME_TOTAL.summary(windowSec, System.currentTimeMillis());
    }

    public static RollingStats.Summary tickTotal(int windowSec) {
        return TICK_TOTAL.summary(windowSec, System.currentTimeMillis());
    }

    public static RollingStats.Summary frameTime(int windowSec) {
        return FRAME_TIME.summary(windowSec, System.currentTimeMillis());
    }

    public static boolean shedding() {
        return tickScheduler.shedding();
    }

    private static long allocSince;
    private static double allocWindowSec;

    /** Starts or stops allocation tracking (the overlay turns it on while open). */
    public static void trackAllocations(boolean on) {
        if (on && !allocTracking) {
            java.util.Arrays.fill(allocBytes, 0);
            allocSince = System.currentTimeMillis();
        }
        allocTracking = on;
    }

    /** Called by the overlay every frame so the per-second allocation figures have a window. */
    public static void updateAllocWindow() {
        allocWindowSec = allocTracking ? (System.currentTimeMillis() - allocSince) / 1000.0 : 0;
    }

    /** {@code /sbs perf reset}. */
    public static void reset() {
        STATS.forEach(RollingStats::reset);
        FRAME_TOTAL.reset();
        TICK_TOTAL.reset();
        FRAME_TIME.reset();
        java.util.Arrays.fill(allocBytes, 0);
        allocSince = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ global counters

    private static final java.util.concurrent.atomic.AtomicLong NETWORK_CALLS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong DISK_WRITES = new java.util.concurrent.atomic.AtomicLong();

    /** Counted by the network choke points; any thread. */
    public static void countNetworkCall() {
        NETWORK_CALLS.incrementAndGet();
    }

    /** Counted by the cache writers; any thread. */
    public static void countDiskWrite() {
        DISK_WRITES.incrementAndGet();
    }

    public static long networkCalls() {
        return NETWORK_CALLS.get();
    }

    public static long diskWrites() {
        return DISK_WRITES.get();
    }
}
