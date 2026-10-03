/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.map.command;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.map.MapModule;

/**
 * The map's hotkey, dispatched from {@code CommandKeyMixin} on a fresh key press while in-world with
 * no screen open.
 *
 * <p>Not gated on the module toggle: opening the map is how you find the toggle again, and a key that
 * silently does nothing is worse than a map that says it is switched off.
 */
public final class MapKeybinds {

    private MapKeybinds() {
    }

    public static void onKeyPressed(int keyCode) {
        var cfg = ConfigManager.getInstance().get().map;
        if (cfg.openKey != 0 && keyCode == cfg.openKey) {
            MapModule.openScreen();
        } else if (cfg.hollowsMapKey != 0 && keyCode == cfg.hollowsMapKey) {
            sbs.modid.client.helper.map.ui.HollowsMapScreen.open();
        }
    }
}
