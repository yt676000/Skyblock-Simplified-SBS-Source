/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.browser;

/**
 * The Web Browser open/close hotkey. Dispatched from {@code CommandKeyMixin} on a fresh key press
 * while in-world with no screen open, so it only ever opens the browser (the {@link
 * sbs.modid.client.helper.browser.BrowserScreen} handles the same key to toggle itself closed).
 */
public final class BrowserKeybinds {

    private BrowserKeybinds() {
    }

    public static void onKeyPressed(int keyCode) {
        var cfg = WebBrowserManager.cfg();
        if (cfg.enabled && cfg.openKey != 0 && keyCode == cfg.openKey) {
            WebBrowserManager.getInstance().toggleScreen();
        }
    }
}
