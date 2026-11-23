/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.localflips.logic;

/**
 * The reconnect schedule for the flip stream: 1 s after the first failure, 2 s after the second,
 * then every 5 s for as long as the program stays away.
 *
 * <p>Short at the start because the usual cause is the flip program restarting its fast mode,
 * which takes a second or two; flat afterwards because "not running" is a normal state for a local
 * program, and a growing delay would only make the player wait longer once it is back.
 * The counter resets on every successful connect.
 */
public final class Backoff {

    private static final long[] SCHEDULE_MS = {1_000, 2_000};
    public static final long STEADY_MS = 5_000;

    private Backoff() {
    }

    /** Over Radmin VPN: 3 s, then doubling up to 30 s - a remote host is away for longer. */
    public static long vpnDelayMs(int failures) {
        if (failures <= 0) {
            return 0;
        }
        return Math.min(30_000L, 3_000L << Math.min(failures - 1, 4));
    }

    /** Delay before the next attempt, after {@code failures} consecutive failures (1 = the first). */
    public static long delayMs(int failures) {
        if (failures <= 0) {
            return 0;
        }
        return failures <= SCHEDULE_MS.length ? SCHEDULE_MS[failures - 1] : STEADY_MS;
    }
}
