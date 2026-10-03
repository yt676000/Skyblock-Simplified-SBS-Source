/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.alert;

/**
 * Bit-mask helpers for a set of {@link AlertChannel}s - the form a per-alert channel selection is
 * stored in.
 *
 * <p>One {@code int} per alert instead of one boolean per channel per alert: adding a channel then
 * costs a constant, not a config migration across every feature that raises an alert.
 */
public final class AlertChannels {

    /** Nothing selected - the alert is off. */
    public static final int NONE = 0;

    /** The conservative in-game pair: seen on screen, heard through our own output. */
    public static final int TITLE_AND_SOUND = AlertChannel.TITLE.bit() | AlertChannel.SOUND.bit();

    private AlertChannels() {
    }

    /** Whether {@code channel} is selected in {@code mask}. */
    public static boolean has(int mask, AlertChannel channel) {
        return (mask & channel.bit()) != 0;
    }

    /** {@code mask} with {@code channel} flipped - what a settings toggle calls. */
    public static int toggle(int mask, AlertChannel channel) {
        return mask ^ channel.bit();
    }

    /** Whether any channel at all is selected. */
    public static boolean any(int mask) {
        return (mask & 0x1F) != 0;
    }

    /** A short human list ("Title, Sound") for a settings label, or "off" when nothing is on. */
    public static String describe(int mask) {
        if (!any(mask)) {
            return "off";
        }
        StringBuilder sb = new StringBuilder(24);
        for (AlertChannel channel : AlertChannel.values()) {
            if (has(mask, channel)) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(channel.displayName());
            }
        }
        return sb.toString();
    }
}
