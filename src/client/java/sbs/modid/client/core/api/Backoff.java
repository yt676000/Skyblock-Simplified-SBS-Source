/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.api;

import java.util.function.DoubleSupplier;

/**
 * Reconnect delays for a long-lived connection: exponential, capped, with jitter.
 *
 * <p>The delay before attempt {@code n} is {@code min(cap, base * 2^n)}, then scaled by a factor
 * in {@code [0.5, 1.0)} from the jitter source. The jitter matters: when the server restarts,
 * every client drops in the same second, and without it they would all come back in the same
 * second again.
 *
 * <p><b>The attempt count only resets after a connection that lasted.</b> If a server accepts the
 * handshake and then drops the socket at once, resetting on every open would reconnect it at the
 * base delay forever. A connection counts as stable once it has stayed open for
 * {@code stableAfterMs}. A shorter one keeps the count it had, so the delays keep growing.
 *
 * <p>Pure and clock-free: callers pass the time in, so the schedule can be tested with a fake
 * clock.
 */
public final class Backoff {

    private final long baseMs;
    private final long capMs;
    private final long stableAfterMs;
    private final DoubleSupplier jitter;

    private int attempt;
    private long connectedAt = -1L;

    /**
     * @param baseMs        the delay before the first retry, before jitter
     * @param capMs         the longest delay ever returned
     * @param stableAfterMs how long a connection must last before the attempt count resets
     * @param jitter        a source of values in {@code [0, 1)}; {@code Math::random} in production
     */
    public Backoff(long baseMs, long capMs, long stableAfterMs, DoubleSupplier jitter) {
        this.baseMs = Math.max(1L, baseMs);
        this.capMs = Math.max(this.baseMs, capMs);
        this.stableAfterMs = Math.max(0L, stableAfterMs);
        this.jitter = jitter;
    }

    /** The delay before the next attempt, in milliseconds, and counts that attempt. */
    public synchronized long nextDelayMs() {
        long raw = baseMs;
        for (int i = 0; i < attempt && raw < capMs; i++) {
            raw *= 2;
        }
        raw = Math.min(raw, capMs);
        attempt++;
        double factor = 0.5 + 0.5 * clamp(jitter.getAsDouble());
        return Math.max(1L, (long) (raw * factor));
    }

    /** A connection opened at {@code nowMs}. */
    public synchronized void onConnected(long nowMs) {
        connectedAt = nowMs;
    }

    /**
     * The connection opened by {@link #onConnected} ended at {@code nowMs}. Resets the attempt
     * count when it lasted at least {@code stableAfterMs}.
     */
    public synchronized void onDisconnected(long nowMs) {
        if (connectedAt >= 0L && nowMs - connectedAt >= stableAfterMs) {
            attempt = 0;
        }
        connectedAt = -1L;
    }

    /** Forgets every failed attempt, for a connection started fresh by the player. */
    public synchronized void reset() {
        attempt = 0;
        connectedAt = -1L;
    }

    /** How many delays have been handed out since the last reset. */
    public synchronized int attempts() {
        return attempt;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || value < 0.0) {
            return 0.0;
        }
        return Math.min(value, 0.999_999);
    }
}
