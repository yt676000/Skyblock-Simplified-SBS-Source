/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.logic;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.inventory.ui.MissingAccessoriesScreen;

/**
 * Opens the missing-accessory screen from its configurable in-world hotkey (unbound by default).
 *
 * <p>Dispatched from {@code CommandKeyMixin}, which only fires on a fresh press with no screen open -
 * the same gate every other SBS hotkey uses, so the key can never fire while typing in chat or
 * browsing another menu.
 */
public final class AccessoryKeybind {

    private AccessoryKeybind() {
    }

    /** Called for every fresh in-world key press. */
    public static void onKeyPressed(int keyCode) {
        int bound = ConfigManager.getInstance().get().accessoryBag.openKey;
        if (bound == 0 || keyCode != bound) {
            return;
        }
        MissingAccessoriesScreen.open();
    }
}
