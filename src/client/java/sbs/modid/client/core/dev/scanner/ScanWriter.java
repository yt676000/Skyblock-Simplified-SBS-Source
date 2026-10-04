/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev.scanner;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.async.SbsExecutors;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The Server Scanner's file output: lines are collected per file on the client thread and handed to
 * {@link SbsExecutors#io()} in batches, so a burst of slot packets is one append per file rather than
 * one write per packet.
 *
 * <p>Order is kept because the IO executor is a single ordered thread: a manifest rewrite queued
 * after an append lands after it. Nothing here touches game state; the lines arrive finished.
 */
final class ScanWriter {

    /** Waiting lines past which {@link #due} asks for a flush before the timer would. */
    static final int FLUSH_EVENTS = 256;

    static final long FLUSH_MS = 250L;

    private final Executor io;
    private final Map<Path, StringBuilder> pending = new LinkedHashMap<>();
    private int pendingLines;
    private long lastFlushAt;

    /** Set from the IO thread when a write fails; the scanner stops the session on its next tick. */
    private volatile String failure;

    ScanWriter() {
        this(SbsExecutors.io());
    }

    ScanWriter(Executor io) {
        this.io = io;
    }

    /** Queues one line for {@code file} and returns its size in bytes (UTF-8, newline included). */
    long append(Path file, String line) {
        pending.computeIfAbsent(file, f -> new StringBuilder(4096)).append(line).append('\n');
        pendingLines++;
        return line.getBytes(StandardCharsets.UTF_8).length + 1L;
    }

    /** Whether the batch should go out now: the interval has passed or too many lines are waiting. */
    boolean due(long now) {
        return pendingLines > 0 && (pendingLines >= FLUSH_EVENTS || now - lastFlushAt >= FLUSH_MS);
    }

    /** Hands every waiting batch to the IO thread. */
    void flush(long now) {
        lastFlushAt = now;
        if (pending.isEmpty()) {
            return;
        }
        for (Map.Entry<Path, StringBuilder> entry : pending.entrySet()) {
            Path file = entry.getKey();
            String text = entry.getValue().toString();
            io.execute(() -> write(file, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        }
        pending.clear();
        pendingLines = 0;
    }

    /** Replaces {@code file} with {@code text} (the manifest), after everything queued before it. */
    void replace(Path file, String text) {
        io.execute(() -> write(file, text, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE));
    }

    String failure() {
        return failure;
    }

    private void write(Path file, String text, StandardOpenOption... options) {
        if (failure != null) {
            return;   // one failure stops the session; do not log it once per batch until then
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8, options);
        } catch (IOException | RuntimeException e) {
            failure = file.getFileName() + ": " + e.getMessage();
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Scanner] Writing {} failed", file, e);
        }
    }
}
