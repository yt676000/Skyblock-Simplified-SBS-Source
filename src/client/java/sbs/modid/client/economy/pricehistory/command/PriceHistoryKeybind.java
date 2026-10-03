/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.pricehistory.command;

import net.minecraft.client.Minecraft;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.economy.pricehistory.ui.PriceHistoryScreen;

/**
 * Dispatches the Item Price History hotkey pressed <b>in-world</b> (no screen open). Called from
 * {@code CommandKeyMixin} on every fresh key press – the same gate the command keybinds use. Opens
 * the fixed, non-movable {@link PriceHistoryScreen} on the general item search; the key is
 * configured in the Item Price History module and defaults to unbound ({@code 0}).
 */
public final class PriceHistoryKeybind {

    private PriceHistoryKeybind() {
    }

    public static void onKeyPressed(int keyCode) {
        int openKey = ConfigManager.getInstance().get().priceHistory.openKey;
        if (openKey == 0 || keyCode != openKey) {
            return;
        }
        Minecraft.getInstance().setScreenAndShow(new PriceHistoryScreen());
    }
}
