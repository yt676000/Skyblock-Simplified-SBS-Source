/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.async;

import sbs.modid.SkyblockSimplifiedSBS;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The mod's shared background threads: one bounded pool for computation and one ordered thread for
 * file writes. All daemon and named, so none of them can keep the game from exiting.
 *
 * <p><b>The rule</b> (also in {@code core/AGENTS.md}): the main thread reads game state into a small
 * immutable snapshot; a worker computes on that snapshot; the main thread applies the result. A
 * worker never touches the level, entities, blocks, screens, the component maps of live
 * {@code ItemStack}s, or render state. {@link #compute} is that shape as a method.
 */
public final class SbsExecutors {

    /** Computation: max(1, cores - 2) threads, so the game's own threads keep their cores. */
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(
            poolSize(), poolSize(), 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
            factory("SBS-Worker", Thread.NORM_PRIORITY - 1));

    /** File writes, in submission order: two writes of one cache can never overtake each other. */
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(
            1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
            factory("SBS-IO", Thread.NORM_PRIORITY - 1));

    static {
        WORKER.allowCoreThreadTimeOut(true);
        IO.allowCoreThreadTimeOut(true);
        // Daemon threads die with the JVM: queued cache writes are finished here, bounded, so a
        // save made just before closing the game still lands - and a stuck write cannot hang exit.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            IO.shutdown();
            try {
                IO.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "SBS-IO-Drain"));
    }

    private SbsExecutors() {
    }

    static int poolSize() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
    }

    /** The computation pool. */
    public static Executor worker() {
        return WORKER;
    }

    /** The ordered file-write thread. */
    public static Executor io() {
        return IO;
    }

    /** Tasks waiting in both queues - a KPI: a growing queue means work arrives faster than it is done. */
    public static int queueDepth() {
        return WORKER.getQueue().size() + IO.getQueue().size();
    }

    /**
     * Snapshot on the calling (main) thread, compute on the worker, apply on the main thread.
     *
     * @param snapshot reads game state; runs NOW, on the caller's thread
     * @param compute  pure work on the snapshot; runs on the worker
     * @param apply    takes the result; runs on {@code main}
     * @param main     the main-thread executor ({@code Minecraft.getInstance()} in game)
     */
    public static <S, R> void compute(Supplier<S> snapshot, Function<S, R> compute, Consumer<R> apply,
                                      Executor main) {
        compute(snapshot, compute, apply, WORKER, main);
    }

    /** {@link #compute} with the worker given - the testable form. */
    static <S, R> void compute(Supplier<S> snapshot, Function<S, R> compute, Consumer<R> apply,
                               Executor worker, Executor main) {
        S taken = snapshot.get();
        worker.execute(() -> {
            R result;
            try {
                result = compute.apply(taken);
            } catch (Throwable t) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Async] background computation failed", t);
                return;
            }
            main.execute(() -> apply.accept(result));
        });
    }

    private static ThreadFactory factory(String name, int priority) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(priority);
            return thread;
        };
    }
}
