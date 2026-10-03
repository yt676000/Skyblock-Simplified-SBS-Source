/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.treasurechest.logic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * This session's treasure chests: how many were opened and what powder they paid, with rates.
 *
 * <p>Counted from the reward lines, not from the tab list's totals - the tab half of the Powder card
 * answers "how much powder did I gain", this answers "how much of it came out of chests". The clock
 * starts at the first chest, not at the first reading, so a session spent mining before the first
 * chest does not dilute the chest rate to nothing.
 *
 * <p>Pure: every call takes the time, so rates can be tested.
 */
public final class ChestSession {

    /** A rate is not stated before the session is this old - anything shorter is noise. */
    public static final long MIN_RATE_MS = 60_000L;

    private int chests;
    private final Map<String, Long> powder = new LinkedHashMap<>();
    private long startedAt = -1L;

    public void chestOpened(long now) {
        start(now);
        chests++;
    }

    public void powder(String type, long amount, long now) {
        if (type == null || amount <= 0) {
            return;
        }
        start(now);
        powder.merge(type, amount, Long::sum);
    }

    public int chests() {
        return chests;
    }

    /** Powder by type, in the order first paid. Read-only view. */
    public Map<String, Long> powder() {
        return Collections.unmodifiableMap(powder);
    }

    /** Whether there is anything to show at all. */
    public boolean isEmpty() {
        return chests == 0 && powder.isEmpty();
    }

    /** {@code value} per hour of this session, or {@code 0} while it is under a minute old. */
    public long perHour(long value, long now) {
        long elapsed = startedAt < 0 ? 0 : now - startedAt;
        if (elapsed < MIN_RATE_MS) {
            return 0L;
        }
        return Math.round(value * 3_600_000.0 / elapsed);
    }

    public void reset() {
        chests = 0;
        powder.clear();
        startedAt = -1L;
    }

    private void start(long now) {
        if (startedAt < 0) {
            startedAt = now;
        }
    }
}
