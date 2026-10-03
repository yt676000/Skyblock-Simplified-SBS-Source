/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.model;

/**
 * A two-step confirm for a destructive button: the first press arms it ("Really delete?"), a second
 * press within {@link #WINDOW_MS} confirms, and it disarms by itself after that - the same shape as
 * SBS Settings' reset-all. Time is passed in, so it is tested without waiting.
 */
public final class ConfirmState {

    public static final long WINDOW_MS = 3_000L;

    private long armedAt = -1;

    /** Handles a press at {@code now}; returns whether this press confirms the action. */
    public boolean press(long now) {
        if (armed(now)) {
            armedAt = -1;
            return true;
        }
        armedAt = now;
        return false;
    }

    public boolean armed(long now) {
        return armedAt >= 0 && now - armedAt < WINDOW_MS;
    }

    public void reset() {
        armedAt = -1;
    }

    /** The button caption at {@code now}. */
    public String label(String idle, long now) {
        return armed(now) ? "Really " + idle.toLowerCase(java.util.Locale.ROOT) + "?" : idle;
    }
}
