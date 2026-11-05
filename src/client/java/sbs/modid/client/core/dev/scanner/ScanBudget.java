/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev.scanner;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * A session's size cap, and each channel's own limits. Pure, unit-tested.
 *
 * <p><b>The session cap</b> counts the bytes the writer actually wrote. Lines are serialized on the
 * IO thread, so {@link #add} is called from there and the counters are atomic; the client thread
 * reads {@link #exceeded} on its tick and stops the session.
 *
 * <p><b>Channel limits</b> ({@link ScanChannel#perSecond}, {@link ScanChannel#maxMb}) are decided on
 * the client thread by {@link #admit}: over its rate a channel drops events until a whole second
 * passes without a drop; over its size it drops everything from then on. Either way the first refusal
 * answers {@link Verdict#THROTTLED}, so the caller writes exactly one {@code throttled} event per
 * episode, and every dropped event is counted for the summary. Nothing is lost silently.
 */
public final class ScanBudget {

    public enum Verdict { OK, THROTTLED, DROP }

    private static final ScanChannel[] CHANNELS = ScanChannel.values();

    private final long limitBytes;
    private final AtomicLong usedBytes = new AtomicLong();
    private final AtomicLongArray channelBytes = new AtomicLongArray(CHANNELS.length);

    // Client thread only.
    private final long[] windowStart = new long[CHANNELS.length];
    private final int[] windowCount = new int[CHANNELS.length];
    private final boolean[] dropsInWindow = new boolean[CHANNELS.length];
    private final boolean[] rateThrottled = new boolean[CHANNELS.length];
    private final boolean[] sizeCapped = new boolean[CHANNELS.length];
    private final long[] dropped = new long[CHANNELS.length];
    private final int[] episodes = new int[CHANNELS.length];

    /** @param limitMb the cap in MB; anything below 1 is treated as 1 */
    public ScanBudget(int limitMb) {
        this.limitBytes = Math.max(1, limitMb) * 1024L * 1024L;
    }

    /**
     * Counts {@code bytes} and answers whether the session may go on. The write that crosses the cap
     * is still counted (and still written): stopping mid-line would leave a broken last line.
     *
     * @return {@code false} once the cap has been reached
     */
    public boolean add(long bytes) {
        return usedBytes.addAndGet(Math.max(0L, bytes)) < limitBytes;
    }

    /** {@link #add} for a line of {@code channel}; IO thread. */
    public boolean add(ScanChannel channel, long bytes) {
        channelBytes.addAndGet(channel.ordinal(), Math.max(0L, bytes));
        return add(bytes);
    }

    public boolean exceeded() {
        return usedBytes.get() >= limitBytes;
    }

    public long usedBytes() {
        return usedBytes.get();
    }

    public long limitBytes() {
        return limitBytes;
    }

    public long channelBytes(ScanChannel channel) {
        return channelBytes.get(channel.ordinal());
    }

    /**
     * Whether one more event of {@code channel} may be written at {@code nowMs}. Client thread.
     * {@link Verdict#THROTTLED} is a refusal too - the event itself is dropped, and the caller writes
     * the one {@code throttled} line instead.
     */
    public Verdict admit(ScanChannel channel, long nowMs) {
        int i = channel.ordinal();
        if (sizeCapped[i]) {
            dropped[i]++;
            return Verdict.DROP;
        }
        if (channel.maxMb > 0 && channelBytes.get(i) >= channel.maxMb * 1024L * 1024L) {
            sizeCapped[i] = true;
            dropped[i]++;
            episodes[i]++;
            return Verdict.THROTTLED;
        }
        if (channel.perSecond <= 0) {
            return Verdict.OK;
        }
        if (nowMs - windowStart[i] >= 1000L) {
            // A whole second without a drop ends the episode: the last window had none, or it is
            // over a second behind us and nothing came at all since.
            if (rateThrottled[i] && (!dropsInWindow[i] || nowMs - windowStart[i] >= 2000L)) {
                rateThrottled[i] = false;
            }
            windowStart[i] = nowMs;
            windowCount[i] = 0;
            dropsInWindow[i] = false;
        }
        if (++windowCount[i] <= channel.perSecond) {
            return Verdict.OK;
        }
        dropped[i]++;
        dropsInWindow[i] = true;
        if (rateThrottled[i]) {
            return Verdict.DROP;
        }
        rateThrottled[i] = true;
        episodes[i]++;
        return Verdict.THROTTLED;
    }

    /** Whether {@code channel} reached its size cap (it records nothing more). */
    public boolean sizeCapped(ScanChannel channel) {
        return sizeCapped[channel.ordinal()];
    }

    /** Events of {@code channel} refused so far. */
    public long dropped(ScanChannel channel) {
        return dropped[channel.ordinal()];
    }

    /** How many times {@code channel} started throttling. */
    public int episodes(ScanChannel channel) {
        return episodes[channel.ordinal()];
    }
}
