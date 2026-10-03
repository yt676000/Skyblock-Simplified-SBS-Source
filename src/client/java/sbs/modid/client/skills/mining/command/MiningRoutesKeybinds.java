/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.command;

import net.minecraft.client.Minecraft;
import sbs.modid.client.skills.mining.logic.MiningRoutesManager;
import sbs.modid.client.skills.mining.ui.MiningRoutesScreen;

/**
 * Mining Routes hotkeys, dispatched from {@code CommandKeyMixin} on a fresh key press while in-world
 * with no screen open: add a waypoint at your feet, or open the routes screen.
 */
public final class MiningRoutesKeybinds {

    private MiningRoutesKeybinds() {
    }

    public static void onKeyPressed(int keyCode) {
        var cfg = MiningRoutesManager.cfg();
        if (!cfg.enabled) {
            return;
        }
        // Only the waypoint key is island-gated: dropping a point somewhere the route can never be
        // drawn is a waypoint you would only find again by deleting it. Opening the routes screen is
        // management, not a world feature, so it stays reachable anywhere.
        if (cfg.addWaypointKey != 0 && keyCode == cfg.addWaypointKey
                && sbs.modid.client.skills.SkillIslands.miningAllowed()) {
            MiningRoutesManager.getInstance().addWaypointAtPlayer();
        }
        if (cfg.openKey != 0 && keyCode == cfg.openKey) {
            Minecraft.getInstance().setScreenAndShow(new MiningRoutesScreen(null));
        }
    }
}
