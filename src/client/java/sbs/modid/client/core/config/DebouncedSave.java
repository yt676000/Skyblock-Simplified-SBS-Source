/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.config;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Coalesces a burst of saves into one write, {@link #DELAY_MS} after the last change.
 *
 * <p>Built for the config: a slider drag calls {@code save()} once per value, and every one of
 * those used to be a full {@code config.json} write on the client thread. Now a change only marks
 * the save dirty; {@link #tick} - driven from the client tick - takes the snapshot on the client
 * thread once the burst has settled and hands it to {@code io}. {@link #flush} writes now, on the
 * caller's thread, for the moments a deferred write could be lost (screen change, game stop).
 *
 * <p>Writes are numbered so a slow background write can never land over a newer one: a flush on the
 * client thread while an older snapshot is still queued makes that older one a no-op.
 *
 * @param <T> an immutable snapshot of what is to be written
 */
public final class DebouncedSave<T> {

    public static final long DELAY_MS = 500L;

    private final Supplier<T> snapshot;
    private final Consumer<T> sink;
    private final Executor io;

    // client thread only
    private boolean dirty;
    private long lastChangeAt;
    private long nextSeq;

    /** Highest sequence written (or discarded); guarded by {@code this}. */
    private long writtenSeq;

    /**
     * @param snapshot runs on the client thread; must return a value the sink can use off-thread
     * @param sink     performs the write; runs on {@code io} or on the flushing thread
     */
    public DebouncedSave(Supplier<T> snapshot, Consumer<T> sink, Executor io) {
        this.snapshot = snapshot;
        this.sink = sink;
        this.io = io;
    }

    /** A change happened; the write follows once {@link #DELAY_MS} pass without another. */
    public void request(long now) {
        dirty = true;
        lastChangeAt = now;
    }

    /** Hands the settled change to the IO executor. Cheap when nothing is pending. */
    public void tick(long now) {
        if (!dirty || now - lastChangeAt < DELAY_MS) {
            return;
        }
        dirty = false;
        long seq = ++nextSeq;
        T taken = snapshot.get();
        io.execute(() -> write(seq, taken));
    }

    /**
     * Writes a pending or still-queued change now, on this thread; once this returns, no deferred
     * write can land any more. Does nothing when nothing is outstanding.
     */
    public void flush() {
        boolean queued;
        synchronized (this) {
            queued = nextSeq > writtenSeq;
        }
        if (!dirty && !queued) {
            return;
        }
        dirty = false;
        write(++nextSeq, snapshot.get());
    }

    /**
     * Forgets the pending change and any queued write - for when the caller has written (or
     * replaced) the file itself and a deferred write would overwrite that.
     */
    public void discard() {
        dirty = false;
        long seq = ++nextSeq;
        synchronized (this) {
            writtenSeq = Math.max(writtenSeq, seq);
        }
    }

    /** Whether a change is waiting to be written. */
    public boolean dirty() {
        return dirty;
    }

    private synchronized void write(long seq, T taken) {
        if (seq <= writtenSeq) {
            return;
        }
        writtenSeq = seq;
        sink.accept(taken);
    }
}
