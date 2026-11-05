/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.sacrifice.logic;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * The last few things that left the inventory, already valued, waiting for a SACRIFICE line to
 * claim one. Pure: the clock is passed in.
 *
 * <p>The item can leave a tick before the chat line or a tick after it, so both sides wait: a line
 * claims a removal up to {@link #WINDOW_MS} old, and a line that found none is retried against
 * removals that arrive within the same window.
 */
public final class RemovalLog {

    /** How long after a removal a chat line still claims it, and how long a line waits for one. */
    public static final long WINDOW_MS = 3_000L;

    /**
     * How far back a SACRIFICE line reaches for a removal made <b>before</b> it. The altar is a menu:
     * the item goes into its slot, then the player confirms - 6 s apart in the maintainer's
     * 2026-10-17 sacrifice, so the 3 s window let the Ender Chestplate expire and it was counted at
     * ~0. The name still has to match the sacrificed item, so a minute claims nothing else.
     */
    public static final long LOOKBACK_MS = 60_000L;
    private static final int MAX = 16;

    /** A removed stack's name key and what it was worth when it went. */
    public record Removal(String key, long value, boolean approx, long at) {
    }

    private final Deque<Removal> recent = new ArrayDeque<>();

    public void add(Removal removal) {
        recent.addLast(removal);
        while (recent.size() > MAX) {
            recent.removeFirst();
        }
    }

    /**
     * The newest removal within the window whose key contains {@code chatKey} (the stack's name may
     * carry a reforge or stars the chat line leaves out), removed from the log so it is claimed once.
     */
    public Removal take(String chatKey, long now) {
        if (chatKey == null || chatKey.isEmpty()) {
            return null;
        }
        Iterator<Removal> it = recent.descendingIterator();
        while (it.hasNext()) {
            Removal r = it.next();
            if (now - r.at() > LOOKBACK_MS) {
                break;
            }
            if (r.at() - now <= WINDOW_MS && r.key().contains(chatKey)) {
                it.remove();
                return r;
            }
        }
        return null;
    }

    public void clear() {
        recent.clear();
    }
}
