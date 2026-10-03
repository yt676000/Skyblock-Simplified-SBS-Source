/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.carry.command;

import sbs.modid.client.combat.carry.CarryModule;
import sbs.modid.client.core.config.ConfigManager;

/**
 * The Carry Tickets open hotkey, dispatched from {@code CommandKeyMixin} on a fresh key press
 * while in-world with no screen open.
 */
public final class CarryKeybinds {

    private CarryKeybinds() {
    }

    public static void onKeyPressed(int keyCode) {
        var cfg = ConfigManager.getInstance().get().carry;
        if (cfg.enabled && cfg.openKey != 0 && keyCode == cfg.openKey) {
            CarryModule.openScreen();
        }
    }
}
