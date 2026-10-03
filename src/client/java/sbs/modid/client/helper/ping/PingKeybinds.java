/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.ping;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

/**
 * The ping keybind: the one entry point {@code core/keybind/KeybindDispatch} calls.
 *
 * <p>The dispatcher has already decided that this is a fresh press, in a world, with no screen open
 * - so a ping cannot fire while the player is typing in chat or reading a menu, which is exactly
 * what the default bind needs.
 *
 * <p><b>The default bind is middle mouse, which vanilla also uses for Pick Block.</b> Nothing is
 * done about that, and that is the design: {@code CommandMouseMixin} cancels nothing, so the press
 * runs both actions and neither knows about the other. Unbinding or overriding a vanilla control
 * from a mod is worse than the collision - it changes a key the player set outside our settings, in
 * a place they will not think to look. The settings row names the collision so anyone it bothers can
 * rebind ours in one click.
 */
public final class PingKeybinds {

    private PingKeybinds() {
    }

    /** Called for every hotkey press the dispatcher accepts. */
    public static void onKeyPressed(int code) {
        SBSConfig.PingSettings cfg = ConfigManager.getInstance().get().ping;
        if (cfg.enabled && cfg.key != 0 && code == cfg.key) {
            PingManager.getInstance().onPressed();
        }
    }
}
