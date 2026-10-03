/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import net.minecraft.network.chat.Component;
import sbs.modid.client.social.chat.logic.SBSChat;

/** Build Tools' chat lines: plain info, amber warnings, one place for the colours. */
public final class BuildChat {

    private static final int WARN = 0xE0A14D;
    private static final int DIM = 0xA0A0A0;

    private BuildChat() {
    }

    public static void info(String text) {
        SBSChat.send(Component.literal(" " + text).withColor(SBSChat.WHITE));
    }

    public static void warn(String text) {
        SBSChat.send(Component.literal(" " + text).withColor(WARN));
    }

    /** A grey follow-up line, for a usage hint under a warning. */
    public static void hint(String text) {
        SBSChat.send(Component.literal("   " + text).withColor(DIM));
    }
}
