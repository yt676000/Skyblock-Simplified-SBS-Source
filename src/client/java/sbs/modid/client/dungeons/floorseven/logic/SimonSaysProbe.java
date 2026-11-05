/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.logic;

import java.util.HashSet;
import java.util.Set;

/**
 * Decides when a piece of button-press feedback is worth one {@code [SBS][SimonSays]} probe line.
 *
 * <p>The solver does not advance its "next" marker on a press, because nobody has yet seen what the
 * device does when it accepts or rejects one. This collects that evidence: after a button goes
 * powered, every distinct kind of reaction near the device (a light change, a sound id) inside
 * {@link #WINDOW_MS} is logged exactly once. A new press opens a new window. Nothing is logged
 * outside a window, so the flash display itself never floods the log.
 */
public final class SimonSaysProbe {

    /** How long after a press a reaction still counts as feedback to it. */
    public static final long WINDOW_MS = 1_500L;

    private final Set<String> seen = new HashSet<>();
    private boolean pressed;
    private long pressMs;

    /** A button near the device went powered. */
    public void onPress(long now) {
        pressed = true;
        pressMs = now;
        seen.clear();
    }

    public boolean inWindow(long now) {
        return pressed && now - pressMs >= 0 && now - pressMs <= WINDOW_MS;
    }

    /** Milliseconds since the press that opened the window. Only meaningful while {@link #inWindow}. */
    public long sincePress(long now) {
        return now - pressMs;
    }

    /** True the first time {@code key} is reported inside the current window; false otherwise. */
    public boolean firstInWindow(String key, long now) {
        return inWindow(now) && seen.add(key);
    }

    public void reset() {
        pressed = false;
        pressMs = 0;
        seen.clear();
    }
}
