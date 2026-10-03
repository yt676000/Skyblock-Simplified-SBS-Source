/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.model;

import java.util.Locale;

/**
 * The small rules that make a selection easy to end: when it is drawn, when the clear gesture and the
 * clear key apply, and what its label says. Pure, so each is unit-tested.
 */
public final class SelectionUx {

    /** The hint shown while only corner 1 is set, and on the help card. */
    public static final String CLEAR_HINT = "Sneak+Right-click air: clear";

    /** How long after a build key or command the selection stays drawn without the stick in hand. */
    public static final long RECENT_USE_MS = 10_000L;

    private SelectionUx() {
    }

    /** {@code "5×3×2 · 30 blocks"}, singular for one, with the clear hint while only corner 1 is set. */
    public static String label(int width, int height, int length, boolean onlyCorner1) {
        long volume = (long) width * height * length;
        String text = width + "×" + height + "×" + length + "  •  " + String.format(Locale.ROOT, "%,d", volume)
                + (volume == 1 ? " block" : " blocks");
        return onlyCorner1 ? text + "  •  " + CLEAR_HINT : text;
    }

    /**
     * Whether the selection box, the live preview and their label are drawn. With "hide unless held"
     * on, only while the stick is in hand or a build key / command was used in the last ten seconds -
     * the selection itself stays either way.
     */
    public static boolean visible(boolean hideUnlessHeld, boolean holdingStick, long lastUseMillis, long now) {
        if (!hideUnlessHeld || holdingStick) {
            return true;
        }
        return lastUseMillis > 0 && now - lastUseMillis < RECENT_USE_MS;
    }

    /**
     * Whether a right-click (or the corner-2 key) clears instead of setting a corner: sneaking, and
     * nothing targeted - aiming at air is what makes it deliberate.
     */
    public static boolean gestureClears(boolean sneaking, boolean somethingTargeted) {
        return sneaking && !somethingTargeted;
    }

    /** Whether the clear key acts: bound, it was this key, and the stick is in hand - never otherwise. */
    public static boolean clearKeyActive(int boundKey, int pressedKey, boolean holdingStick) {
        return boundKey != 0 && boundKey == pressedKey && holdingStick;
    }
}
