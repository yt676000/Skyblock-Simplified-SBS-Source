/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.dev.DevMode;

/**
 * Central gate + sink for all dungeon debug output.
 *
 * <p>Everything here is a no-op unless {@link DevMode#ACTIVE} – so with developer mode off the whole
 * dungeon system is completely silent (no chat, no console spam). When on, {@link #chat(String)} prints
 * to both the game chat and the log, so the exact failure point can be traced live.
 */
public final class DungeonDebug {

    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    private DungeonDebug() {
    }

    /** True only while developer mode is enabled. */
    public static boolean enabled() {
        // DEV-ONLY: debug chat only
        return DevMode.ACTIVE;
    }

    /** Prints {@code message} to chat + log, but only in developer mode. Colour codes (§) allowed. */
    public static void chat(String message) {
        if (!enabled()) {
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][DungeonDebug] {}", strip(message));
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.literal("§8[§bSBS§8]§r " + message));
        }
    }

    private static String strip(String text) {
        return text == null ? "" : text.replaceAll(SECTION_SIGN + ".", "");
    }
}
