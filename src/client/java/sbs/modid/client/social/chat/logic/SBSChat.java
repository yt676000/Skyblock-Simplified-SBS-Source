/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Central helper for every client-side chat message SBS sends.
 *
 * <p>Guarantees a single, consistent look across the whole mod (and any future module):
 * the {@code [SBS]} prefix is always painted in the SBS accent color {@code #3FB4FF} using
 * Minecraft's RGB text API ({@link MutableComponent#withColor(int)}), not legacy formatting
 * codes. Callers build a colored {@code body} component (starting with a leading space) and
 * pass it here; the prefix is prepended and the line is shown client-side via the player's
 * own {@code sendSystemMessage} (no packets are sent to the server).
 */
public final class SBSChat {

    /** Accent color of the {@code [SBS]} prefix (matches {@code SBSTheme.ACCENT}). */
    public static final int PREFIX_COLOR = 0x3FB4FF;

    /** Default body color (item names, surrounding text, ...). */
    public static final int WHITE = 0xFFFFFF;

    private SBSChat() {
    }

    /** The {@code [SBS]} prefix in the accent color. */
    public static MutableComponent prefix() {
        return Component.literal("[SBS]").withColor(PREFIX_COLOR);
    }

    /** Builds a full line: the prefix followed by {@code body} (which should start with a space). */
    public static MutableComponent line(Component body) {
        return prefix().append(body);
    }

    /** Sends a pre-colored body as a client-side message (prefix prepended). */
    public static void send(Component body) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        minecraft.player.sendSystemMessage(line(body));
    }

    /** Convenience for a plain white message. */
    public static void send(String text) {
        send(Component.literal(" " + text).withColor(WHITE));
    }
}
