/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting;

import sbs.modid.client.core.command.SBSCommands;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

/**
 * The Hunting module's in-world hotkeys: open the Hunting menu, open Shard Fusion.
 *
 * <p>Each is a key plus the command it runs, both configurable – the key so it fits your layout, and
 * the command because Hypixel renames these menus between updates and a hard-coded one would rot.
 * Execution goes through {@link SBSCommands#run(String)}, the same path a typed command takes.
 *
 * <p>Dispatched from {@code CommandKeyMixin}, which only fires on a fresh press with no screen open.
 * The in-<i>menu</i> fusion keys (accept / repeat) live in {@link ShardFusion} instead, because they
 * must fire precisely while a screen IS open.
 */
public final class HuntingKeybinds {

    private HuntingKeybinds() {
    }

    /** Called for every fresh in-world key press. */
    public static void onKeyPressed(int keyCode) {
        SBSConfig.HuntingSettings cfg = ConfigManager.getInstance().get().hunting;
        if (keyCode == 0) {
            return;
        }
        if (keyCode == cfg.huntingKey) {
            run(cfg.huntingCommand);
            return;
        }
        if (keyCode == cfg.shardFusionKey) {
            run(cfg.shardFusionCommand);
        }
    }

    private static void run(String command) {
        if (command != null && !command.isBlank()) {
            SBSCommands.run(command.trim());
        }
    }
}
