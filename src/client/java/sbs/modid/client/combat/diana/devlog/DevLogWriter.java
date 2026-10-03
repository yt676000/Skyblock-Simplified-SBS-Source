/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.diana.devlog;

import sbs.modid.SkyblockSimplifiedSBS;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One capture's writer: a bounded queue in front of a daemon thread that numbers each event and
 * appends it to the {@code .jsonl} as one line.
 *
 * <h2>Why its own thread and not {@code SbsExecutors.io()}</h2>
 *
 * <p>{@code core/AGENTS.md} sends file writes through the shared ordered IO thread unless the work
 * needs its own ordering or lifetime, and this needs both. A capture streams hundreds of events a
 * second for as long as it runs; on the shared thread every config and cache save would queue behind
 * it. And the shared queue is unbounded, where this one has to stay bounded: when the disk cannot keep
 * up, an event is <b>dropped and counted</b>, never allowed to grow memory or block the client thread.
 *
 * <h2>What it writes besides the events</h2>
 *
 * <ul>
 *   <li>A {@code stats} event every five seconds: events per second by type over the window, written
 *   and dropped totals, queue depth. Built here, because only this thread knows the totals.</li>
 *   <li>A {@code session_end} event when stopped, after everything queued before the stop.</li>
 * </ul>
 *
 * <p>The file is flushed every 250 ms and on stop, so a crash loses at most a quarter of a second.
 * After closing it the thread exports the {@code .json} array ({@link DevLogExport}) unless the file
 * is over the size limit the caller set.
 */
final class DevLogWriter {

    /** Events the queue holds before it starts dropping. */
    static final int CAPACITY = 50_000;

    static final long FLUSH_MS = 250L;
    static final long STATS_MS = 5_000L;

    /** Events taken from the queue per wake-up, so a backlog is written in batches. */
    private static final int BATCH = 4_096;

    /** What a stopped writer produced. {@code json} is {@code null} when no array was written. */
    record Outcome(Path jsonl, Path json, long written, long dropped, DevLogExport.Result export,
                   String failure, String exportSkipped) {
    }

    private final Path file;
    private final Supplier<DevLogEvents.Stamp> stamp;
    private final long startedAt;
    private final ArrayBlockingQueue<DevLogEvent> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final CountDownLatch finished = new CountDownLatch(1);
    private final Thread thread;

    /** Lines written so far. Only the writer thread writes it. */
    private volatile long written;

    /** Set once, by {@link #stop}; the thread drains and finishes when it sees it. */
    private volatile String stopReason;
    private volatile long exportLimitBytes = -1;
    private volatile Consumer<Outcome> onFinished;
    private volatile Outcome outcome;
    private volatile String failure;

    /**
     * @param file  the {@code .jsonl} to create; it must not exist yet
     * @param stamp the current time and capture tick, for the events this thread builds itself
     */
    DevLogWriter(Path file, Supplier<DevLogEvents.Stamp> stamp) {
        this.file = file;
        this.stamp = stamp;
        this.startedAt = System.currentTimeMillis();
        this.thread = new Thread(this::run, "SBS-DianaLog");
        this.thread.setDaemon(true);
        this.thread.setPriority(Thread.NORM_PRIORITY - 1);
    }

    void start() {
        thread.start();
    }

    /** Queues an event, or drops and counts it when the queue is full. Never blocks. */
    boolean offer(DevLogEvent event) {
        if (stopReason != null || failure != null || !queue.offer(event)) {
            dropped.incrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * Asks the thread to finish: everything already queued is written, then {@code session_end}, then
     * the file is closed and exported. Returns at once; {@code onFinished} runs on the writer thread.
     *
     * @param exportLimitBytes skip the export for a {@code .jsonl} larger than this; negative for no limit
     */
    void stop(String reason, long exportLimitBytes, Consumer<Outcome> onFinished) {
        this.exportLimitBytes = exportLimitBytes;
        this.onFinished = onFinished;
        this.stopReason = reason == null ? "stopped" : reason;
    }

    /** Waits for a stopped writer to finish, up to {@code millis}. True when it has. */
    boolean awaitFinished(long millis) {
        try {
            return finished.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    Path file() {
        return file;
    }

    long written() {
        return written;
    }

    long dropped() {
        return dropped.get();
    }

    int queued() {
        return queue.size();
    }

    /** Why the thread stopped writing, or {@code null} while it is healthy. */
    String failure() {
        return failure;
    }

    Outcome outcome() {
        return outcome;
    }

    // ------------------------------------------------------------------
    // The thread
    // ------------------------------------------------------------------

    private void run() {
        long seq = 0;
        Map<String, long[]> window = new HashMap<>();
        long windowStart = System.currentTimeMillis();
        long lastFlush = windowStart;
        List<DevLogEvent> batch = new ArrayList<>(BATCH);
        boolean interrupted = false;

        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            while (true) {
                DevLogEvent first = null;
                if (interrupted) {
                    first = queue.poll();
                } else {
                    try {
                        first = queue.poll(FLUSH_MS, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        // Nothing interrupts this thread on purpose; treat it as a stop and drain.
                        interrupted = true;
                        if (stopReason == null) {
                            stopReason = "interrupted";
                        }
                    }
                }
                if (first != null) {
                    seq = write(out, seq, first, window);
                    queue.drainTo(batch, BATCH);
                    for (DevLogEvent event : batch) {
                        seq = write(out, seq, event, window);
                    }
                    batch.clear();
                }
                long now = System.currentTimeMillis();
                if (now - windowStart >= STATS_MS) {
                    seq = write(out, seq, stats(window, now - windowStart), null);
                    window.clear();
                    windowStart = now;
                }
                if (now - lastFlush >= FLUSH_MS) {
                    out.flush();
                    lastFlush = now;
                }
                if (stopReason != null && queue.isEmpty()) {
                    break;
                }
            }
            seq = write(out, seq, DevLogEvents.sessionEnd(stamp.get(), stopReason, seq, dropped.get(),
                    System.currentTimeMillis() - startedAt), null);
            out.flush();
        } catch (IOException | RuntimeException e) {
            failure = e.toString();
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Diana] the capture writer stopped: {}", file, e);
        }

        finish(failure);
    }

    /** Numbers and writes one event. A line that cannot be serialised becomes an error line instead. */
    private long write(BufferedWriter out, long seq, DevLogEvent event, Map<String, long[]> window)
            throws IOException {
        long next = seq + 1;
        String line;
        try {
            line = DevLogEvents.toJsonLine(next, event);
        } catch (RuntimeException e) {
            line = DevLogEvents.toJsonLine(next, DevLogEvents.error(new DevLogEvents.Stamp(event.t(),
                    event.tick()), "devlog", "serialise " + event.type(), null, e, null, null));
        }
        out.write(line);
        out.write('\n');
        written = next;
        if (window != null) {
            window.computeIfAbsent(event.type(), k -> new long[1])[0]++;
        }
        return next;
    }

    private DevLogEvent stats(Map<String, long[]> window, long windowMs) {
        Map<String, Double> perSecond = new TreeMap<>();
        double seconds = Math.max(1L, windowMs) / 1000.0;
        window.forEach((type, n) -> perSecond.put(type, Math.round(n[0] / seconds * 100.0) / 100.0));
        return DevLogEvents.stats(stamp.get(), windowMs, perSecond, written, dropped.get(), queue.size());
    }

    private void finish(String failed) {
        Path json = null;
        DevLogExport.Result export = null;
        String skipped = null;
        try {
            long size = Files.size(file);
            long limit = exportLimitBytes;
            if (limit >= 0 && size > limit) {
                skipped = "the capture is " + (size >> 20) + " MB, over the " + (limit >> 20)
                        + " MB limit for converting it now; /sbs devlog diana export converts it later";
            } else {
                Path target = DevLogExport.arrayPathFor(file);
                export = DevLogExport.export(file, target);
                json = target;
            }
        } catch (IOException | RuntimeException e) {
            skipped = "the export failed: " + e;
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Diana] exporting {} failed", file, e);
        }
        outcome = new Outcome(file, json, written, dropped.get(), export, failed, skipped);
        finished.countDown();
        Consumer<Outcome> callback = onFinished;
        if (callback != null) {
            try {
                callback.accept(outcome);
            } catch (RuntimeException e) {
                SkyblockSimplifiedSBS.LOGGER.error("[SBS][Diana] reporting the finished capture failed", e);
            }
        }
    }
}
