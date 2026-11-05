/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev.scanner;

import sbs.modid.SkyblockSimplifiedSBS;
import com.google.gson.JsonElement;
import sbs.modid.client.core.async.SbsExecutors;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The Server Scanner's file output: lines are collected per file on the client thread and handed to
 * {@link SbsExecutors#io()} in batches, so a burst of slot packets is one append per file rather than
 * one write per packet.
 *
 * <p>Order is kept because the IO executor is a single ordered thread: a manifest rewrite queued
 * after an append lands after it. Nothing here touches game state.
 *
 * <p><b>JSON events are serialized on the IO thread</b> ({@link #append(Path, JsonElement, ScanChannel)}):
 * the client thread only builds the small tree, which must not be changed after it is handed over.
 * The bytes each one came to are reported back through {@link Sink} from the IO thread, which is
 * what the session's size cap and the per-channel caps count.
 */
final class ScanWriter {

    /** Waiting lines past which {@link #due} asks for a flush before the timer would. */
    static final int FLUSH_EVENTS = 256;

    static final long FLUSH_MS = 250L;

    /** Told, on the IO thread, how many bytes a serialized line of a channel came to. */
    interface Sink {
        void written(ScanChannel channel, long bytes);
    }

    private record Line(Object value, ScanChannel channel) {
    }

    private final Executor io;
    private final Sink sink;
    private final Map<Path, List<Line>> pending = new LinkedHashMap<>();
    private int pendingLines;
    private long lastFlushAt;

    /** Set from the IO thread when a write fails; the scanner stops the session on its next tick. */
    private volatile String failure;

    /** The writer a session uses: {@link SbsExecutors#io()}, sizes reported to {@code sink}. */
    static ScanWriter onIo(Sink sink) {
        return new ScanWriter(SbsExecutors.io(), sink);
    }

    ScanWriter(Executor io) {
        this(io, (channel, bytes) -> { });
    }

    ScanWriter(Executor io, Sink sink) {
        this.io = io;
        this.sink = sink;
    }

    /**
     * Queues one finished line for {@code file} and returns its size in bytes (UTF-8, newline
     * included). Not reported to the {@link Sink}: the caller already knows the size.
     */
    long append(Path file, String line) {
        pending.computeIfAbsent(file, f -> new ArrayList<>()).add(new Line(line, null));
        pendingLines++;
        return line.getBytes(StandardCharsets.UTF_8).length + 1L;
    }

    /**
     * Queues one event for {@code file}, serialized later on the IO thread. {@code event} is not
     * touched again by the caller once it is here.
     */
    void append(Path file, JsonElement event, ScanChannel channel) {
        pending.computeIfAbsent(file, f -> new ArrayList<>()).add(new Line(event, channel));
        pendingLines++;
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
        for (Map.Entry<Path, List<Line>> entry : pending.entrySet()) {
            Path file = entry.getKey();
            List<Line> lines = entry.getValue();
            io.execute(() -> write(file, serialize(lines), StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        }
        pending.clear();
        pendingLines = 0;
    }

    /** Replaces {@code file} with {@code text} (the manifest), after everything queued before it. */
    void replace(Path file, String text) {
        io.execute(() -> write(file, text, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE));
    }

    /** IO thread: the batch as text, each JSON line's size reported to the sink as it is made. */
    private String serialize(List<Line> lines) {
        StringBuilder text = new StringBuilder(lines.size() * 160);
        for (Line line : lines) {
            String value = line.value() instanceof String s ? s : line.value().toString();
            text.append(value).append('\n');
            if (line.channel() != null) {
                sink.written(line.channel(), value.getBytes(StandardCharsets.UTF_8).length + 1L);
            }
        }
        return text.toString();
    }

    /** Runs {@code task} on the IO thread once everything queued before it is written. */
    void afterWrites(Runnable task) {
        io.execute(task);
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
