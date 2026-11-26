/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.localflips.command;

import sbs.modid.client.economy.localflips.logic.LocalFlipFinder;

/**
 * The Local Flip Finder's two keys, both unbound by default: open the flip window, and open the
 * newest still-valid SOFORT find. Called from {@code KeybindDispatch} on a fresh press in-world with
 * no screen open. One press is one action - the newest find is opened once, never repeated.
 */
public final class LocalFlipKeybinds {

    private LocalFlipKeybinds() {
    }

    public static void onKeyPressed(int code) {
        if (code == 0 || !LocalFlipFinder.available()) {
            return;
        }
        var cfg = LocalFlipFinder.cfg();
        if (cfg.openOverlayKey != 0 && code == cfg.openOverlayKey) {
            sbs.modid.client.economy.localflips.ui.LocalFlipsWindow.toggle();
        } else if (cfg.openNewestKey != 0 && code == cfg.openNewestKey && cfg.enabled) {
            LocalFlipFinder.getInstance().openNewest();
        }
    }
}
