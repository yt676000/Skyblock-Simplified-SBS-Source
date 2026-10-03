/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.settings;

/**
 * "Click once to arm, again to confirm" for a settings action that throws something away.
 *
 * <p>{@code ui/AGENTS.md} does not allow a hand-built configuration to be destroyed without a
 * confirmation or an undo, and a settings row has nowhere to put a dialog. Arming the button itself
 * is the affordance that fits: the first click costs nothing and says what the second will do, and
 * an arm that is left alone expires rather than lying in wait for an unrelated click minutes later.
 */
public final class ConfirmClick {

    /** How long an armed action stays armed. Long enough to read the warning, short enough to forget. */
    public static final long WINDOW_MS = 5_000L;

    private long armedAt;

    /** Whether a second click right now would go through. */
    public boolean armed() {
        return armedAt != 0 && System.currentTimeMillis() - armedAt < WINDOW_MS;
    }

    /** Arms on the first click, reports {@code true} on the confirming one. */
    public boolean click() {
        if (armed()) {
            armedAt = 0;
            return true;
        }
        armedAt = System.currentTimeMillis();
        return false;
    }

    /** The label's right-hand side: what clicking will do right now. */
    public String state(String armedText, String idleText) {
        return armed() ? armedText : idleText;
    }
}
