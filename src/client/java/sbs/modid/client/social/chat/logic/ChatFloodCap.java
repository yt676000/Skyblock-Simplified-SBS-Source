/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat.logic;

import sbs.modid.SkyblockSimplifiedSBS;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * At most {@link #MAX_LINES} chat lines per feature inside {@link #WINDOW_MS}; the rest are dropped.
 *
 * <p>A second line of defence behind {@link SBSChat#ownLineInFlight()}. That flag stops the one
 * feedback loop known to have happened - a parser answering its own line - but a feature reacting to
 * game input can flood the chat by other routes too, and a chat that grows by a hundred lines a second
 * is a client that stops drawing frames. Dropping the excess costs a few lines in a case that is a bug
 * anyway; it is logged once per flood so the bug is still found.
 *
 * <p>Pure: the clock is a parameter, so the cap is tested without waiting.
 */
public final class ChatFloodCap {

    /** Lines one feature may send inside {@link #WINDOW_MS}. */
    public static final int MAX_LINES = 5;

    public static final long WINDOW_MS = 1_000L;

    /** Send times inside the current window, per feature. */
    private final Map<String, ArrayDeque<Long>> recent = new HashMap<>();

    /** Features whose current flood has already been logged. Cleared when the flood ends. */
    private final Set<String> reported = new HashSet<>();

    /** Whether {@code feature} may send a line at {@code now}; a refusal is logged once per flood. */
    public synchronized boolean allow(String feature, long now) {
        String key = feature == null ? "unknown" : feature;
        ArrayDeque<Long> times = recent.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_MS) {
            times.pollFirst();
        }
        if (times.size() < MAX_LINES) {
            times.addLast(now);
            if (times.size() == 1) {
                reported.remove(key);
            }
            return true;
        }
        if (reported.add(key)) {
            try {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Chat] flood from {} suppressed", key);
            } catch (RuntimeException | LinkageError ignored) {
                // No logger (unit tests without one): the refusal is what matters.
            }
        }
        return false;
    }

    /** Whether a flood from {@code feature} has been logged and has not ended yet. */
    public synchronized boolean flooding(String feature) {
        return reported.contains(feature);
    }
}
