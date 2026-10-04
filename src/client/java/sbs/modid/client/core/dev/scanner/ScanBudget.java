/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev.scanner;

/**
 * A session's size cap. Counts the bytes handed to the writer, not the bytes on disk: the two only
 * differ by what is still queued, and counting at hand-over lets the cap stop a session before a
 * burst is written rather than after. Pure, unit-tested.
 */
public final class ScanBudget {

    private final long limitBytes;
    private long usedBytes;

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
        usedBytes += Math.max(0L, bytes);
        return usedBytes < limitBytes;
    }

    public boolean exceeded() {
        return usedBytes >= limitBytes;
    }

    public long usedBytes() {
        return usedBytes;
    }

    public long limitBytes() {
        return limitBytes;
    }
}
