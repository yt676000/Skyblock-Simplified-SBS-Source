/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev.scanner;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Every non-menu event waits here for {@link #HOLD_MS} before it is written, which buys two things at
 * once: an identical event inside that window (same chat line, same sound at the same spot) is folded
 * into the waiting one as {@code "repeat": n} instead of being written again, and the files still come
 * out in the order the events happened, because the queue releases strictly first-in first-out.
 *
 * <p>A folded event keeps its first {@code t} and gains {@code tLast}. A repeat arriving after the
 * first was released starts a new event - the window is short on purpose, so a sound that repeats all
 * minute reads as one line a second with its count, not as one line for the minute.
 *
 * <p>Client thread only. Pure apart from Gson, unit-tested.
 */
final class ScanQueue {

    static final long HOLD_MS = 1000L;

    private static final class Held {
        final ScanChannel channel;
        final String key;
        final JsonObject event;
        final long at;
        int repeat = 1;
        long lastT;

        Held(ScanChannel channel, String key, JsonObject event, long at) {
            this.channel = channel;
            this.key = key;
            this.event = event;
            this.at = at;
        }
    }

    private final ArrayDeque<Held> queue = new ArrayDeque<>();
    private final Map<String, Held> byKey = new HashMap<>();

    /**
     * Queues {@code event}, or folds it into a waiting one with the same {@code key}.
     *
     * @param key   what makes two events "the same"; {@code null} never folds
     * @param t     the event's session time, kept as {@code tLast} when it folds
     * @return {@code true} when it was queued as a new event, {@code false} when it was folded
     */
    boolean offer(ScanChannel channel, String key, JsonObject event, long nowMs, long t) {
        if (key != null) {
            Held waiting = byKey.get(key);
            if (waiting != null) {
                waiting.repeat++;
                waiting.lastT = t;
                return false;
            }
        }
        Held held = new Held(channel, key, event, nowMs);
        queue.addLast(held);
        if (key != null) {
            byKey.put(key, held);
        }
        return true;
    }

    /** Hands every event older than {@link #HOLD_MS} to {@code out}, oldest first. */
    void release(long nowMs, BiConsumer<ScanChannel, JsonObject> out) {
        while (!queue.isEmpty() && nowMs - queue.peekFirst().at >= HOLD_MS) {
            out.accept(queue.peekFirst().channel, finish(queue.pollFirst()));
        }
    }

    /** Hands everything still waiting to {@code out} - the session is ending. */
    void drain(BiConsumer<ScanChannel, JsonObject> out) {
        while (!queue.isEmpty()) {
            out.accept(queue.peekFirst().channel, finish(queue.pollFirst()));
        }
    }

    int size() {
        return queue.size();
    }

    private JsonObject finish(Held held) {
        if (held.key != null) {
            byKey.remove(held.key, held);
        }
        if (held.repeat > 1) {
            held.event.addProperty("repeat", held.repeat);
            held.event.addProperty("tLast", held.lastT);
        }
        return held.event;
    }
}
