/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.contest;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Crops per minute over the last {@link #WINDOW_MS}, from samples of the contest's collected count,
 * and the projection at the end. Pure: the clock is passed in.
 *
 * <p>The projection is an <b>estimate</b> and the card says so: it assumes the last minute's pace
 * holds, which it does not across a pest, a visitor or a lobby hop.
 */
final class ContestRate {

    static final long WINDOW_MS = 60_000L;

    private record Sample(long at, long collected) {
    }

    private final Deque<Sample> samples = new ArrayDeque<>();

    /** One reading of the collected count. A count that went down starts the window over. */
    void add(long now, long collected) {
        if (collected < 0) {
            return;
        }
        Sample last = samples.peekLast();
        if (last != null && collected < last.collected()) {
            samples.clear();
        }
        samples.addLast(new Sample(now, collected));
        while (samples.size() > 1 && now - samples.peekFirst().at() > WINDOW_MS) {
            samples.removeFirst();
        }
    }

    void clear() {
        samples.clear();
    }

    /** Crops per minute over the window, or -1 until it spans at least 10 seconds. */
    double perMinute() {
        Sample first = samples.peekFirst();
        Sample last = samples.peekLast();
        if (first == null || last == null || last.at() - first.at() < 10_000L) {
            return -1;
        }
        return (last.collected() - first.collected()) * 60_000.0 / (last.at() - first.at());
    }

    /** The collected count at the end if this pace holds, or -1 when unknown. */
    long projection(long collected, int secondsLeft) {
        double rate = perMinute();
        if (rate < 0 || collected < 0 || secondsLeft < 0) {
            return -1;
        }
        return collected + Math.round(rate * secondsLeft / 60.0);
    }
}
