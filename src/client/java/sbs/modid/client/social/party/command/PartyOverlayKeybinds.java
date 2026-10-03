/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.party.command;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.social.party.PartyOverlayModule;

/**
 * The Party Overlay open hotkey, dispatched from {@code CommandKeyMixin} on a fresh key press while
 * in-world with no screen open (unbound by default).
 */
public final class PartyOverlayKeybinds {

    private PartyOverlayKeybinds() {
    }

    public static void onKeyPressed(int keyCode) {
        var cfg = ConfigManager.getInstance().get().partyOverlay;
        if (cfg.enabled && cfg.openKey != 0 && keyCode == cfg.openKey) {
            PartyOverlayModule.openScreen();
        }
    }
}
