/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.config;

/**
 * When a throttled cache file may be written: never without a change, at most once per
 * {@link #INTERVAL_MS} for routine changes, and at once for a one-shot change that nothing will
 * report again (a heal, a capture, a new proof). A routine request inside the interval is remembered
 * and flushed by the next {@link #pending} check rather than dropped.
 *
 * <p>Pure - the clock is passed in - so the rule "no write without a change" is a unit test, not a
 * comment. The one-second heartbeat this replaced came from a caller that reported a change every
 * second; the throttle is the second line of defence, the caller's change test the first.
 */
public final class SaveThrottle {

    public static final long INTERVAL_MS = 4000L;

    private long lastWriteAt = Long.MIN_VALUE / 2;
    private boolean dirty;

    /**
     * A change happened. Returns whether to write now.
     *
     * @param oneShot a change that will not be reported again, so it must not wait
     */
    public synchronized boolean request(boolean oneShot, long now) {
        dirty = true;
        return oneShot || now - lastWriteAt >= INTERVAL_MS;
    }

    /** Whether a deferred change is waiting and the interval has passed. */
    public synchronized boolean pending(long now) {
        return dirty && now - lastWriteAt >= INTERVAL_MS;
    }

    /** Whether any change is waiting at all. */
    public synchronized boolean dirty() {
        return dirty;
    }

    /** The write happened. */
    public synchronized void written(long now) {
        dirty = false;
        lastWriteAt = now;
    }
}
