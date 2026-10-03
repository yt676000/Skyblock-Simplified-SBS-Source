/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.command;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

/**
 * Dispatches the two route-waypoint hotkeys (standing / looking). Called from {@code CommandKeyMixin}
 * on every fresh key press that happens in-world with no screen open – the same gate the command
 * keybinds use. Both keys are configured in the Dungeons module and default to unbound ({@code 0}),
 * so nothing fires until the player assigns them.
 */
public final class RouteKeybinds {

    private RouteKeybinds() {
    }

    public static void onKeyPressed(int keyCode) {
        SBSConfig.DungeonsSettings dungeons = ConfigManager.getInstance().get().dungeons;
        if (dungeons == null || keyCode == 0) {
            return;
        }
        if (keyCode == dungeons.routeStandingKey) {
            RouteActions.beginStanding();
        } else if (keyCode == dungeons.routeLookingKey) {
            RouteActions.beginLooking();
        }
    }
}
