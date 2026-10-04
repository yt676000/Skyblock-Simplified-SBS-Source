/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.experiment.logic;

/**
 * The "input starts now" cue of Chronomatron and Ultrasequencer: the moment the clock replaces the
 * glowstone, a "GO" label shows in the title row for {@link #LABEL_MS} and the board frame flashes
 * for {@link #FLASH_MS}. Fed the input state every tick; only the rising edge starts it.
 */
final class StartCue {

    static final long LABEL_MS = 1_200L;
    static final long FLASH_MS = 300L;

    private boolean input;
    private long startedAt = Long.MIN_VALUE / 2;

    /** One tick: whether input is running now. */
    void update(boolean inputNow, long now) {
        if (inputNow && !input) {
            startedAt = now;
        }
        input = inputNow;
    }

    void reset() {
        input = false;
        startedAt = Long.MIN_VALUE / 2;
    }

    boolean labelShowing(long now) {
        return input && now - startedAt < LABEL_MS;
    }

    boolean flashShowing(long now) {
        return input && now - startedAt < FLASH_MS;
    }
}
