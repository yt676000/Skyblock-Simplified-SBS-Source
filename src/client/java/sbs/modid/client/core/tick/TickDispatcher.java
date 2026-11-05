/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.tick;

import sbs.modid.client.core.perf.TierScheduler;

import java.util.List;

/**
 * Runs a fixed list of {@link TickTask}s in order, skipping the ones whose gate says they are not
 * relevant on this tick. Pure - the context and the meter are passed in - so the gating, the order
 * and the run-on are unit-tested without a game.
 *
 * <p><b>Run-on.</b> A task keeps running for {@link #RUN_ON_TICKS} after its gate stops passing, and
 * every task gets that window when the world changes ({@link TickContext#worldEpoch()}). Most
 * features notice leaving their context <i>inside</i> their own tick - they clear markers, end a
 * session, drop entity references when the player goes away - and the run-on is what keeps those
 * edges firing exactly as before, without a separate reset hook per feature. Two seconds covers every
 * leave edge in the audited consumers (the slowest confirms after 1.2 s); a consumer whose edge is
 * slower than that keeps a broader gate.
 *
 * <p><b>Fence.</b> A task body that throws is switched off for the session and reported once through
 * {@link Meter#failed}; the other tasks keep running and nothing escapes into {@code Minecraft.tick},
 * so one feature can never end the game. It catches what {@code DianaGuard} catches -
 * {@link RuntimeException}, {@link LinkageError} and {@link StackOverflowError} (a recursion bug in
 * that body, fully unwound by the time it gets here) - and deliberately lets the rest of the
 * {@link VirtualMachineError} family ({@link OutOfMemoryError}, {@link InternalError}) through: those
 * mean the VM itself is in trouble and nothing the mod does next can be trusted.
 *
 * <p>Client thread only. A steady tick allocates nothing.
 */
public final class TickDispatcher {

    /** How long a task keeps running after its gate stopped passing, or after a world change. */
    public static final int RUN_ON_TICKS = 40;

    /** Where the dispatcher reports what it ran and what it skipped. */
    public interface Meter {
        /** Whether a NORMAL / BACKGROUND task may run now (the budget). */
        boolean allow(TickTask task);

        /** The task's gate kept it from running this tick. */
        void skipped(TickTask task);

        /** Around a measured task's body. Never nested. */
        void begin(TickTask task);

        void end(TickTask task);

        /** The task's body threw; the dispatcher has switched it off for the session. Once per task. */
        void failed(TickTask task, Throwable thrown);
    }

    private final TickTask[] tasks;
    private final int[] runOn;
    private final boolean[] disabled;
    private final Meter meter;
    private int epoch;
    private boolean started;

    public TickDispatcher(List<TickTask> tasks, Meter meter) {
        this.tasks = tasks.toArray(new TickTask[0]);
        this.runOn = new int[this.tasks.length];
        this.disabled = new boolean[this.tasks.length];
        this.meter = meter;
    }

    /** One tick: every task in registration order, gated on {@code context}. */
    public void run(TickContext context) {
        boolean transition = !started || context.worldEpoch() != epoch;
        started = true;
        epoch = context.worldEpoch();
        for (int i = 0; i < tasks.length; i++) {
            TickTask task = tasks[i];
            if (disabled[i]) {
                continue;
            }
            if (transition) {
                runOn[i] = RUN_ON_TICKS;
            }
            if (task.gate().test(context)) {
                runOn[i] = RUN_ON_TICKS;
            } else if (runOn[i] > 0) {
                runOn[i]--;
            } else {
                meter.skipped(task);
                continue;
            }
            if (task.tier() != TierScheduler.Tier.CRITICAL && !meter.allow(task)) {
                continue;
            }
            try {
                if (task.measured()) {
                    meter.begin(task);
                    try {
                        task.body().run();
                    } finally {
                        meter.end(task);
                    }
                } else {
                    task.body().run();
                }
            } catch (RuntimeException | LinkageError | StackOverflowError thrown) {
                disabled[i] = true;
                meter.failed(task, thrown);
            }
        }
    }

    /** Whether {@code name} has been switched off by a failure; for the tests and the debug command. */
    public boolean disabled(String name) {
        for (int i = 0; i < tasks.length; i++) {
            if (disabled[i] && tasks[i].name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** How many tasks this dispatcher runs; for the tests and the debug command. */
    public int size() {
        return tasks.length;
    }
}
